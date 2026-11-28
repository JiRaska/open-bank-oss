// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@TestProfile(McpNativePasskeyAgePolicyApiIT.Enabled::class)
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
    restrictToAnnotatedClass = true,
)
class McpNativePasskeyAgePolicyApiIT {
    class Enabled : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.party.sandbox-mcp-native-age-policy" to "true",
            "openbank.party.sandbox-mcp-native-age-exceptions" to "true",
        )
    }

    @Inject lateinit var dataSource: DataSource

    @BeforeEach
    fun resetAudit() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM mcp_native_passkey_sandbox_age_policy_audit") }
        }
    }

    @Test
    @TestSecurity(user = "staff-fixture", roles = ["ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "staff-fixture-id"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "staff-fixture"),
        ],
    )
    fun `administrator updates and revokes global policy with durable audit`() {
        val now = Instant.now()
        given().get(PATH).then().statusCode(404)
        given().contentType("application/json").body(body(0, now.plusSeconds(1800))).put(PATH).then().statusCode(400)
        given().contentType("application/json").body(body(1, now.plusSeconds(90000))).put(PATH).then().statusCode(400)
        assertThat(audit()).isEmpty()

        given().contentType("application/json").body(body(1, now.plusSeconds(1800))).put(PATH).then()
            .statusCode(200).body("minimumAgeMinutes", equalTo(1)).body("reason", equalTo("Fixture"))
        given().get(PATH).then().statusCode(200)
        given().delete(PATH).then().statusCode(204)
        given().get(PATH).then().statusCode(404)
        assertThat(audit()).containsExactly("staff-fixture-id" to 1, "staff-fixture-id" to null)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-fixture-id"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `only live eligible parties receive the effective shared rule from the Edge endpoint`() {
        val party = UUID.randomUUID()
        val path = "/api/v1/parties/$party/mcp-native-passkey-age-policy/effective"
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO parties (party_id, party_type, classification, status, legal_name, email)
                VALUES (?, 'INDIVIDUAL', 'CUSTOMER', 'ACTIVE', 'Contract fixture', ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, party)
                it.setString(2, "$party@example.test")
                it.executeUpdate()
            }
            given().get(path).then().statusCode(404)
            given().get("/api/v1/parties/$party/mcp-native-passkey-age-exception/effective").then().statusCode(404)
            conn.createStatement().use {
                it.executeUpdate(
                    """
                    INSERT INTO mcp_native_passkey_sandbox_age_policy_audit (actor_subject, minimum_age_minutes, expires_at, reason)
                    VALUES ('staff-fixture-id', 1, statement_timestamp() + interval '1 hour', 'Fixture')
                    """.trimIndent(),
                )
            }
            given().get(path).then().statusCode(200).body("minimumAgeMinutes", equalTo(1)).body("reason", nullValue())
            given().get(
                "/api/v1/parties/${UUID.randomUUID()}/mcp-native-passkey-age-policy/effective",
            ).then().statusCode(404)
            given().get(PATH).then().statusCode(403)
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "other-workload-fixture-id"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `another operator workload cannot use either effective endpoint`() = assertEffectiveStatus(403)

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "edge-fixture-id"),
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `Edge without the operator role cannot use either effective endpoint`() = assertEffectiveStatus(403)

    private fun assertEffectiveStatus(status: Int) {
        val party = UUID.randomUUID()
        for (resource in listOf("mcp-native-passkey-age-policy", "mcp-native-passkey-age-exception")) {
            given().get("/api/v1/parties/$party/$resource/effective").then().statusCode(status)
        }
    }

    private fun body(minutes: Int, expiresAt: Instant) =
        """{"minimumAgeMinutes":$minutes,"expiresAt":"$expiresAt","reason":"Fixture"}"""

    private fun audit(): List<Pair<String, Int?>> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT actor_subject, minimum_age_minutes FROM mcp_native_passkey_sandbox_age_policy_audit ORDER BY sequence_id",
        ).use {
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1) to rows.getObject(2, Int::class.javaObjectType))
                }
            }
        }
    }

    private companion object {
        const val PATH = "/api/v1/parties/mcp-native-passkey-age-policy"
    }
}
