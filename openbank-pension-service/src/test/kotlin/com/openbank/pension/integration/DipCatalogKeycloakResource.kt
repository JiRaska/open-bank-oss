// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.readText

/** Isolated Keycloak issuer with synthetic, short-lived service and human credentials. */
class DipCatalogKeycloakResource : QuarkusTestResourceLifecycleManager {
    private var container: GenericContainer<*>? = null
    private var postgres: PostgreSQLContainer<*>? = null
    private var catalog: Process? = null
    private var started = false
    private val evidenceScopeId = UUID.randomUUID().toString()

    @Suppress("LongMethod") // Lifecycle setup starts three isolated processes before Quarkus augmentation.
    override fun start(): Map<String, String> {
        val secret = UUID.randomUUID().toString()
        val realm = testRealm(secret)
        val keycloak = GenericContainer(DockerImageName.parse(upstreamImage()))
            .withExposedPorts(8080)
            .withCommand("start-dev", "--import-realm")
            .withCopyToContainer(
                Transferable.of(ObjectMapper().writeValueAsBytes(realm)),
                "/opt/keycloak/data/import/$REALM-realm.json",
            )
            .waitingFor(Wait.forHttp("/realms/$REALM/.well-known/openid-configuration").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2))
        container = keycloak
        try {
            keycloak.start()
            started = true
            TestInfrastructureEvidence.record("keycloak", keycloak.dockerImageName, "started", evidenceScopeId)
            val issuer = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}/realms/$REALM"
            val pg = PostgreSQLContainer("postgres:18.6-alpine")
                .withDatabaseName("dip_catalog_e2e")
                .withUsername("openbank")
                .withPassword(secret)
            pg.start()
            postgres = pg
            val port = ServerSocket(0).use { it.localPort }
            val runner = Path.of(System.getProperty("openbank.test.catalog.runner"))
            require(Files.isRegularFile(runner)) { "catalog fast-jar runner is missing: $runner" }
            val logs = Files.createTempFile("dip-catalog-e2e-", ".log")
            val process = ProcessBuilder(
                "java",
                "-Dquarkus.http.port=$port",
                "-jar",
                runner.toAbsolutePath().toString(),
            )
                .directory(runner.parent.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logs.toFile())
            process.environment().apply {
                put("QUARKUS_OIDC_AUTH_SERVER_URL", issuer)
                put("POSTGRES_USER", "openbank")
                put("POSTGRES_PASSWORD", secret)
                put("REACTIVE_URL", "postgresql://${pg.host}:${pg.getMappedPort(5432)}/dip_catalog_e2e")
                put("JDBC_URL", pg.jdbcUrl)
                put("OPENBANK_CATALOG_PACKS", "retirement")
                put("OPENBANK_CATALOG_BANK_V1_COMPATIBILITY_ENABLED", "false")
                put("AUTHZ_ENFORCE", "false")
            }
            catalog = process.start()
            val base = "http://127.0.0.1:$port"
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
            var ready = false
            while (System.nanoTime() < deadline && catalog!!.isAlive) {
                ready = runCatching {
                    http.send(
                        HttpRequest.newBuilder(URI.create("$base/api/v2/product-types")).GET().build(),
                        HttpResponse.BodyHandlers.discarding(),
                    ).statusCode() == 401
                }.getOrDefault(false)
                if (ready) break
                Thread.sleep(500)
            }
            check(ready) { "catalog fast-jar failed readiness; inspect $logs" }
            return mapOf(
                "quarkus.oidc.auth-server-url" to issuer,
                "openbank.test.pension.issuer" to issuer,
                "openbank.test.pension.secret" to secret,
                "openbank.test.pension.catalog-url" to base,
                "quarkus.rest-client.product-catalog.url" to base,
                "quarkus.oidc-client.auth-server-url" to issuer,
                "quarkus.oidc-client.client-id" to "openbank-pension",
                "quarkus.oidc-client.credentials.secret" to secret,
                "quarkus.oidc-client.grant.type" to "client",
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    private fun testRealm(secret: String) = mapOf(
        "realm" to REALM,
        "enabled" to true,
        "roles" to mapOf(
            "realm" to listOf(
                mapOf("name" to "ROLE_API"),
                mapOf("name" to "ROLE_OPERATOR"),
                mapOf("name" to "ROLE_PENSION_LEGAL_COUNSEL"),
                mapOf("name" to "ROLE_PENSION_PRODUCT_OWNER"),
            ),
        ),
        "clientScopes" to testClientScopes(),
        "clients" to listOf(
            client("openbank-pension", secret, catalogRead = true),
            client("unrelated-service", secret, catalogRead = true),
            client("unmarked-service", secret, catalogRead = true).toMutableMap().apply {
                put("defaultClientScopes", listOf("basic", "catalog:read"))
            },
            adminClient(secret),
        ),
        "users" to listOf(
            serviceUser("openbank-pension"),
            serviceUser("unrelated-service"),
            serviceUser("unmarked-service").toMutableMap().apply {
                put("realmRoles", emptyList<String>())
            },
            humanUser("legal-reviewer", secret, "ROLE_PENSION_LEGAL_COUNSEL"),
            humanUser("product-reviewer", secret, "ROLE_PENSION_PRODUCT_OWNER"),
            humanUser("catalog-maker", secret, "ROLE_OPERATOR"),
            humanUser("catalog-checker", secret, "ROLE_OPERATOR"),
        ),
    )

    private fun testClientScopes() = listOf(
        mapOf(
            "name" to "basic",
            "protocol" to "openid-connect",
            "protocolMappers" to listOf(
                mapOf(
                    "name" to "sub",
                    "protocol" to "openid-connect",
                    "protocolMapper" to "oidc-sub-mapper",
                    "consentRequired" to false,
                    "config" to mapOf("access.token.claim" to "true"),
                ),
            ),
        ),
        mapOf(
            "name" to "profile",
            "protocol" to "openid-connect",
            "protocolMappers" to listOf(
                mapOf(
                    "name" to "username",
                    "protocol" to "openid-connect",
                    "protocolMapper" to "oidc-usermodel-attribute-mapper",
                    "consentRequired" to false,
                    "config" to mapOf(
                        "user.attribute" to "username",
                        "access.token.claim" to "true",
                        "claim.name" to "preferred_username",
                        "jsonType.label" to "String",
                    ),
                ),
            ),
        ),
        mapOf(
            "name" to "roles",
            "protocol" to "openid-connect",
            "protocolMappers" to listOf(
                mapOf(
                    "name" to "realm roles",
                    "protocol" to "openid-connect",
                    "protocolMapper" to "oidc-usermodel-realm-role-mapper",
                    "consentRequired" to false,
                    "config" to mapOf(
                        "access.token.claim" to "true",
                        "claim.name" to "realm_access.roles",
                        "jsonType.label" to "String",
                        "multivalued" to "true",
                    ),
                ),
            ),
        ),
    ) + listOf("catalog:read", "catalog:author", "catalog:publish", "pension:legal-approve", "pension:product-approve")
        .map { mapOf("name" to it, "protocol" to "openid-connect") }

    override fun stop() {
        catalog?.let {
            it.destroy()
            if (!it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
        catalog = null
        postgres?.stop()
        postgres = null
        container?.let {
            it.stop()
            if (started) TestInfrastructureEvidence.record("keycloak", it.dockerImageName, "stopped", evidenceScopeId)
        }
        container = null
        started = false
    }

    private fun upstreamImage(): String {
        val source = Path.of(System.getProperty("openbank.test.keycloak-dockerfile")).readText()
        val version = source.lineSequence().first { it.startsWith("ARG KEYCLOAK_VERSION=") }.substringAfter('=')
        val image = source.lineSequence().first { it.startsWith("FROM quay.io/keycloak/keycloak:") }.split(' ')[1]
        return image.replace("\${KEYCLOAK_VERSION}", version)
    }

    private fun client(id: String, secret: String, catalogRead: Boolean) = mapOf(
        "clientId" to id,
        "secret" to secret,
        "publicClient" to false,
        "serviceAccountsEnabled" to true,
        "defaultClientScopes" to listOf("basic", "profile", "roles") +
            if (catalogRead) listOf("catalog:read") else emptyList(),
    )

    private fun serviceUser(id: String) = mapOf(
        "username" to "service-account-$id",
        "enabled" to true,
        "serviceAccountClientId" to id,
        "realmRoles" to listOf("ROLE_API"),
    )

    /** Password grant is enabled only in this disposable test realm to obtain real human JWTs. */
    private fun adminClient(secret: String) = mapOf(
        "clientId" to "openbank-admin-ui",
        "secret" to secret,
        "publicClient" to false,
        "standardFlowEnabled" to true,
        "directAccessGrantsEnabled" to true,
        "serviceAccountsEnabled" to false,
        "defaultClientScopes" to listOf("basic", "profile", "roles"),
        "optionalClientScopes" to listOf(
            "pension:legal-approve",
            "pension:product-approve",
            "catalog:read",
            "catalog:author",
            "catalog:publish",
        ),
    )

    private fun humanUser(username: String, password: String, role: String) = mapOf(
        "username" to username,
        "email" to "$username@example.invalid",
        "emailVerified" to true,
        "firstName" to "Synthetic",
        "lastName" to "Reviewer",
        "enabled" to true,
        "requiredActions" to emptyList<String>(),
        "credentials" to listOf(mapOf("type" to "password", "value" to password, "temporary" to false)),
        "realmRoles" to listOf(role),
    )

    private companion object {
        const val REALM = "catalog-pension-oidc-proof"
    }
}
