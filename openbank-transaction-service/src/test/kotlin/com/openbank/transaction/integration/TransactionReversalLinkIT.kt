// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * #8841: a reversal must say WHAT it reversed. The V2 compliance columns `reversal_of` /
 * `is_reversal` existed — documented, drawn in the ER diagram — with no code path ever assigning
 * them, so every unit test that never read the row back looked fine. This IT drives the real REST
 * flow and asserts the linkage on the PERSISTED row with plain JDBC, because the defect is
 * precisely a field the code never touched.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.transaction.it.PostgresRedpandaTestResource::class)
class TransactionReversalLinkIT {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String

    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var jdbcUser: String

    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var jdbcPassword: String

    private val today = LocalDate.now().toString()

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a reversal persists reversal_of and is_reversal on the real row`() {
        val source = UUID.randomUUID()
        val target = UUID.randomUUID()
        val originalId = RestAssured.given()
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "${UUID.randomUUID()}",
                  "type": "TRANSFER",
                  "sourceAccountId": "$source",
                  "targetAccountId": "$target",
                  "amount": "150.00",
                  "currencyCode": "CZK",
                  "description": "Reversal-link IT transfer",
                  "valueDate": "$today"
                }
                """.trimIndent(),
            )
            .post("/api/v1/transactions")
            .then().statusCode(201)
            .extract().jsonPath().getString("id")

        val reversalId = RestAssured.given()
            .contentType("application/json")
            .body("""{"idempotencyKey": "${UUID.randomUUID()}", "reason": "Reversal-link IT"}""")
            .post("/api/v1/transactions/$originalId/reverse")
            .then().statusCode(200)
            .extract().jsonPath().getString("id")

        DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.createStatement().executeQuery(
                "SELECT reversal_of, is_reversal FROM transactions WHERE id = '$reversalId'",
            ).use { rs ->
                assertThat(rs.next()).describedAs("reversal row exists").isTrue()
                assertThat(rs.getObject("reversal_of").toString()).isEqualTo(originalId)
                assertThat(rs.getBoolean("is_reversal")).isTrue()
            }
            // The original carries no link in the other direction and is not itself a reversal.
            conn.createStatement().executeQuery(
                "SELECT is_reversal FROM transactions WHERE id = '$originalId'",
            ).use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getBoolean("is_reversal")).isFalse()
            }
        }
    }
}
