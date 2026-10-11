// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.readText

/** Disposable real OIDC issuer and enforced policy endpoint; all identities are synthetic. */
class AccountPensionOidcTestResource : QuarkusTestResourceLifecycleManager {
    private var keycloak: GenericContainer<*>? = null
    private var policy: HttpServer? = null
    private val decisions = ConcurrentLinkedQueue<String>()

    override fun start(): Map<String, String> {
        val secret = UUID.randomUUID().toString()
        val realm = testRealm(secret)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/data/openbank/rest/allow") { exchange ->
            exchange.use {
                val input = ObjectMapper().readTree(exchange.requestBody)["input"]
                val principal = input["principal"]
                val allow = input["action"].asText() == "account.verifyOwnership" &&
                    principal["id"].asText() == "service-account-openbank-pension" &&
                    principal["roles"].any { it.asText() == "ROLE_PENSION_ACCOUNT_VERIFY" }
                decisions.add("${principal["id"].asText()}:$allow")
                val response = """{"result":$allow}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.write(response)
            }
        }
        server.createContext("/__decisions") { exchange ->
            exchange.use {
                val response = ObjectMapper().writeValueAsBytes(decisions.toList())
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.write(response)
            }
        }
        policy = server
        server.start()
        try {
            val container = GenericContainer(DockerImageName.parse(keycloakImage()))
                .withExposedPorts(8080)
                .withCommand("start-dev", "--import-realm")
                .withCopyToContainer(
                    Transferable.of(ObjectMapper().writeValueAsBytes(realm)),
                    "/opt/keycloak/data/import/$REALM-realm.json",
                )
                .waitingFor(Wait.forHttp("/realms/$REALM/.well-known/openid-configuration").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(2))
            keycloak = container
            container.start()
            val issuer = "http://${container.host}:${container.getMappedPort(8080)}/realms/$REALM"
            return mapOf(
                "quarkus.oidc.auth-server-url" to issuer,
                "openbank.test.pension.issuer" to issuer,
                "openbank.test.pension.secret" to secret,
                "opa.url" to "http://127.0.0.1:${server.address.port}",
                "openbank.test.pension.policy-url" to "http://127.0.0.1:${server.address.port}",
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        keycloak?.stop()
        keycloak = null
        policy?.stop(0)
        policy = null
    }

    private fun testRealm(secret: String): Map<String, Any> = mapOf(
        "realm" to REALM,
        "enabled" to true,
        "roles" to mapOf(
            "realm" to listOf("ROLE_API", "ROLE_PENSION_ACCOUNT_VERIFY").map { mapOf("name" to it) },
        ),
        "clientScopes" to clientScopes(),
        "clients" to listOf("openbank-pension", "unrelated-service", "overgranted-service").map { id ->
            mapOf(
                "clientId" to id,
                "secret" to secret,
                "publicClient" to false,
                "serviceAccountsEnabled" to true,
                "defaultClientScopes" to listOf("profile", "roles"),
            )
        },
        "users" to listOf("openbank-pension", "unrelated-service", "overgranted-service").map { id ->
            mapOf(
                "username" to "service-account-$id",
                "enabled" to true,
                "serviceAccountClientId" to id,
                "realmRoles" to if (id == "unrelated-service") {
                    listOf("ROLE_API")
                } else {
                    listOf("ROLE_API", "ROLE_PENSION_ACCOUNT_VERIFY")
                },
            )
        },
    )

    private fun clientScopes(): List<Map<String, Any>> = listOf(
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
    )

    private fun keycloakImage(): String {
        val dockerfile = Path.of(System.getProperty("openbank.test.keycloak-dockerfile")).readText()
        val version = dockerfile.lineSequence().first { it.startsWith("ARG KEYCLOAK_VERSION=") }.substringAfter('=')
        val image = dockerfile.lineSequence().first { it.startsWith("FROM quay.io/keycloak/keycloak:") }.split(' ')[1]
        return image.replace("\${KEYCLOAK_VERSION}", version)
    }

    private companion object {
        const val REALM = "account-pension-oidc-proof"
    }
}
