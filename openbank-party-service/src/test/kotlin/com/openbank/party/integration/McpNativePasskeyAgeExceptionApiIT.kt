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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@TestProfile(McpNativePasskeyAgeExceptionApiIT.Enabled::class)
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
    restrictToAnnotatedClass = true,
)
class McpNativePasskeyAgeExceptionApiIT {
    class Enabled : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            mapOf("openbank.party.sandbox-mcp-native-age-exceptions" to "true")
    }

    @Inject lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "staff-fixture", roles = ["ROLE_ADMIN"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "staff-fixture-id"),
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "preferred_username", value = "staff-fixture"),
        ],
    )
    fun `only active synthetic parties and eligibility changes invalidate a stored age exception`() {
        val customer = seed("CUSTOMER")
        val synthetic = seed("SYNTHETIC")
        val body = """{"minimumAgeMinutes":1,"expiresAt":"${Instant.now().plusSeconds(1800)}","reason":"Fixture"}"""

        given().get(path(customer)).then().statusCode(403)
        given().contentType("application/json").body(body).put(path(customer)).then().statusCode(400)
        assertThat(audit(customer)).isEmpty()

        given().get(path(synthetic)).then().statusCode(404)
        given().contentType("application/json").body(body).put(path(synthetic)).then()
            .statusCode(200)
            .body("partyId", equalTo(synthetic.toString()))
            .body("minimumAgeMinutes", equalTo(1))
            .body("reason", equalTo("Fixture"))
        given().get(path(synthetic)).then().statusCode(200)
        assertThat(audit(synthetic)).containsExactly("staff-fixture-id" to 1)

        classify(synthetic, "CUSTOMER")
        given().get(path(synthetic)).then().statusCode(403)
        classify(synthetic, "SYNTHETIC")
        given().get(path(synthetic)).then().statusCode(404)
        assertThat(audit(synthetic)).containsExactly("staff-fixture-id" to 1, "system:party-eligibility" to null)
    }

    private fun seed(classification: String): UUID = UUID.randomUUID().also { party ->
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO parties (party_id, party_type, classification, status, legal_name, email)
                VALUES (?, 'INDIVIDUAL', ?, 'ACTIVE', 'Contract fixture', ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, party)
                it.setString(2, classification)
                it.setString(3, "$party@example.test")
                it.executeUpdate()
            }
        }
    }

    private fun classify(party: UUID, classification: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE parties SET classification = ? WHERE party_id = ?").use {
                it.setString(1, classification)
                it.setObject(2, party)
                assertThat(it.executeUpdate()).isEqualTo(1)
            }
        }
    }

    private fun audit(party: UUID): List<Pair<String, Int?>> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT actor_subject, minimum_age_minutes FROM mcp_native_passkey_age_exception_audit
            WHERE party_id = ? ORDER BY sequence_id
            """.trimIndent(),
        ).use {
            it.setObject(1, party)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1) to rows.getObject(2, Int::class.javaObjectType))
                }
            }
        }
    }

    private fun path(party: UUID): String = "/api/v1/parties/$party/mcp-native-passkey-age-exception"
}
