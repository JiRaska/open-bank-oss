// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import com.openbank.fraud.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class FraudInvestigationCaseIT {
    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `review score opens one case and close never creates a fraud finding`() {
        val scoreId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val counterpartyId = UUID.randomUUID()
        seedScore(scoreId, "REVIEW", accountId, counterpartyId)

        val opened = given().contentType("application/json")
            .header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"$scoreId"}""")
            .post("/api/v1/fraud/cases").then().statusCode(201)
            .header("Cache-Control", "no-store")
            .body("status", equalTo("OPEN"))
            .body("revision", equalTo(1))
            .extract().response()
        val caseId = UUID.fromString(opened.jsonPath().getString("caseId"))
        assertThat(opened.body.asString()).doesNotContain(accountId.toString(), counterpartyId.toString())

        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"$scoreId"}""")
            .post("/api/v1/fraud/cases").then().statusCode(201)
            .body("caseId", equalTo(caseId.toString()))
        assertThat(caseCount(scoreId)).isEqualTo(1)
        assertThat(outboxCount(caseId)).isZero()

        given().header("X-Investigation-Purpose", PURPOSE)
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(200)
            .body("status", equalTo("CLOSED_NO_FINDING"))
            .body("revision", equalTo(2))
        given().header("X-Investigation-Purpose", PURPOSE)
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(200)
            .body("revision", equalTo(2))
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/$caseId").then().statusCode(200)
            .body("status", equalTo("CLOSED_NO_FINDING"))
            .body("$", not(hasKey("accountId")))
            .body("$", not(hasKey("counterpartyId")))
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `a score without REVIEW or an account cannot anchor a case`() {
        val allowedScoreId = UUID.randomUUID()
        val missingAccountScoreId = UUID.randomUUID()
        seedScore(allowedScoreId, "ALLOW", UUID.randomUUID(), null)
        seedScore(missingAccountScoreId, "REVIEW", null, null)
        for (scoreId in listOf(allowedScoreId, missingAccountScoreId, UUID.randomUUID())) {
            given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
                .body("""{"scoreId":"$scoreId"}""")
                .post("/api/v1/fraud/cases").then().statusCode(404)
        }
    }

    @Test
    @TestSecurity(user = "fraud-reader", roles = ["ROLE_API"])
    fun `service identity cannot open or read cases`() {
        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"${UUID.randomUUID()}"}""")
            .post("/api/v1/fraud/cases").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/${UUID.randomUUID()}").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "fraud-operator", roles = ["ROLE_OPERATOR"])
    fun `operator cannot read or close another case through the admin source API`() {
        val caseId = UUID.randomUUID()
        given().contentType("application/json").header("X-Investigation-Purpose", PURPOSE)
            .body("""{"scoreId":"${UUID.randomUUID()}"}""")
            .post("/api/v1/fraud/cases").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .get("/api/v1/fraud/cases/$caseId").then().statusCode(403)
        given().header("X-Investigation-Purpose", PURPOSE)
            .post("/api/v1/fraud/cases/$caseId/close-without-finding").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "fraud-admin", roles = ["ROLE_ADMIN"])
    fun `missing purpose is rejected before case lookup`() {
        given().get("/api/v1/fraud/cases/${UUID.randomUUID()}").then().statusCode(400)
    }

    private fun seedScore(scoreId: UUID, verdict: String, accountId: UUID?, counterpartyId: UUID?) {
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO fraud_scores (score_id, amount, currency, rail, account_id,
                   counterparty_id, verdict, score, reasons_json, rule_version)
                   VALUES (?, 100.00, 'CZK', 'TEST', ?, ?, ?, 1, '[]', 'test')
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, scoreId)
                stmt.setObject(2, accountId)
                stmt.setObject(3, counterpartyId)
                stmt.setString(4, verdict)
                stmt.executeUpdate()
            }
        }
    }

    private fun caseCount(scoreId: UUID): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM fraud_investigation_cases WHERE score_id = ?").use { stmt ->
            stmt.setObject(1, scoreId)
            stmt.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun outboxCount(caseId: UUID): Int = connection().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM fraud_outbox WHERE aggregate_id = ?").use { stmt ->
            stmt.setObject(1, caseId)
            stmt.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun connection(): java.sql.Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }

    private companion object {
        const val PURPOSE = "FRAUD_INVESTIGATION"
    }
}
