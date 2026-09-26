// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.sca.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.readText

/** Isolated upstream Keycloak at the version/digest used by the deployment's image build. */
class ScaOidcTestResource : QuarkusTestResourceLifecycleManager {
    private var container: GenericContainer<*>? = null
    private var started = false

    override fun start(): Map<String, String> {
        val password = UUID.randomUUID().toString()
        val secret = UUID.randomUUID().toString()
        val roles = listOf("ROLE_OPERATOR", "ROLE_API", "ROLE_CUSTOMER").map { mapOf("name" to it) }
        val realm = mapOf(
            "realm" to REALM,
            "enabled" to true,
            "roles" to mapOf("realm" to roles),
            "clients" to listOf(
                mapOf(
                    "clientId" to "sca-proof-browser",
                    "publicClient" to true,
                    "directAccessGrantsEnabled" to true,
                    "defaultClientScopes" to listOf("profile", "roles"),
                ),
                serviceClient("openbank-services", secret),
                serviceClient("openbank-edge", secret),
            ),
            "users" to listOf(
                user("sca-oidc-maker", "ROLE_OPERATOR", password),
                user("sca-oidc-checker", "ROLE_OPERATOR", password),
                user("sca-oidc-customer", "ROLE_CUSTOMER", password),
                serviceUser("openbank-services"),
                serviceUser("openbank-edge"),
            ),
        )
        val keycloak = GenericContainer(DockerImageName.parse(upstreamImage()))
            .withExposedPorts(HTTP_PORT)
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
            TestInfrastructureEvidence.record("keycloak", keycloak.dockerImageName, "started")
            val issuer = "http://${keycloak.host}:${keycloak.getMappedPort(HTTP_PORT)}/realms/$REALM"
            return mapOf(
                "quarkus.oidc.auth-server-url" to issuer,
                "quarkus.oidc.client-id" to "openbank-services",
                "quarkus.oidc.credentials.secret" to secret,
                "openbank.test.oidc.issuer" to issuer,
                "openbank.test.oidc.password" to password,
                "openbank.test.oidc.secret" to secret,
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        container?.let {
            it.stop()
            if (started) TestInfrastructureEvidence.record("keycloak", it.dockerImageName, "stopped")
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

    private fun user(name: String, role: String, password: String) = mapOf(
        "username" to name,
        "enabled" to true,
        "firstName" to "Synthetic",
        "lastName" to "Operator",
        "email" to "$name@example.invalid",
        "emailVerified" to true,
        "realmRoles" to listOf(role),
        "credentials" to listOf(mapOf("type" to "password", "value" to password, "temporary" to false)),
    )

    private fun serviceClient(id: String, secret: String) = mapOf(
        "clientId" to id,
        "secret" to secret,
        "publicClient" to false,
        "serviceAccountsEnabled" to true,
        "defaultClientScopes" to listOf("profile", "roles"),
    )

    private fun serviceUser(id: String) = mapOf(
        "username" to "service-account-$id",
        "enabled" to true,
        "serviceAccountClientId" to id,
        "realmRoles" to listOf("ROLE_OPERATOR", "ROLE_API"),
    )

    private companion object {
        const val REALM = "sca-approval-proof"
        const val HTTP_PORT = 8080
    }
}
