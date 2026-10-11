// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.readText

/** Isolated Keycloak issuer with synthetic, short-lived service and human credentials. */
class CatalogPensionOidcTestResource : QuarkusTestResourceLifecycleManager {
    private var container: GenericContainer<*>? = null

    override fun start(): Map<String, String> {
        val secret = UUID.randomUUID().toString()
        val realm = mapOf(
            "realm" to REALM,
            "enabled" to true,
            "roles" to mapOf(
                "realm" to listOf(
                    mapOf("name" to "ROLE_API"),
                    mapOf("name" to "ROLE_PENSION_LEGAL_COUNSEL"),
                    mapOf("name" to "ROLE_PENSION_PRODUCT_OWNER"),
                ),
            ),
            "clientScopes" to listOf(
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
            ) + listOf("catalog:read", "pension:legal-approve", "pension:product-approve")
                .map { mapOf("name" to it, "protocol" to "openid-connect") },
            "clients" to listOf(
                client("openbank-pension", secret, catalogRead = true),
                client("unrelated-service", secret, catalogRead = false),
                adminClient(secret),
            ),
            "users" to listOf(
                serviceUser("openbank-pension"),
                serviceUser("unrelated-service"),
                humanUser("legal-reviewer", secret, "ROLE_PENSION_LEGAL_COUNSEL"),
                humanUser("product-reviewer", secret, "ROLE_PENSION_PRODUCT_OWNER"),
            ),
        )
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
            val issuer = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}/realms/$REALM"
            return mapOf(
                "quarkus.oidc.auth-server-url" to issuer,
                "openbank.test.pension.issuer" to issuer,
                "openbank.test.pension.secret" to secret,
            )
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        container?.stop()
        container = null
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
        "defaultClientScopes" to listOf("profile", "roles") + if (catalogRead) listOf("catalog:read") else emptyList(),
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
        "defaultClientScopes" to listOf("profile", "roles"),
        "optionalClientScopes" to listOf("pension:legal-approve", "pension:product-approve"),
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
