// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(com.openbank.fraud.it.PostgresRedisTestResource::class)
class FraudApiIT {

    @Test
    fun `GET info returns service metadata`() {
        val body = (Given { this } When { get("/api/v1/info") } Then { statusCode(200) }).extract().body().asString()
        assertThat(body).contains("openbank-fraud-service")
    }

    @Test
    fun `GET health ready returns UP`() {
        Given { this } When { get("/q/health/ready") } Then { statusCode(200) }
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST score returns the baseline ALLOW verdict`() {
        val payload = """
            {
              "amount": "1250.00",
              "currency": "CZK",
              "rail": "SEPA_INSTANT",
              "accountId": "00000000-0000-0000-0000-0000000000a1",
              "counterpartyId": "00000000-0000-0000-0000-0000000000b2"
            }
        """.trimIndent()

        Given {
            contentType("application/json")
            body(payload)
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
            body("verdict", equalTo("ALLOW"))
            body("ruleVersion", equalTo("v4"))
            body("score", equalTo(0))
        }
    }

    @Test
    fun `POST score without a role is rejected`() {
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":"CZK","rail":"SEPA"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(401)
        }
    }

    /**
     * ADR-0377 D3 through real HTTP and the real payee_history table: a first above-threshold
     * payment to a new `counterparty` is held for review; once an allowed payment to the same
     * account has established it, the same amount no longer trips the new-payee rule.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST score keys the new-payee rule on the counterparty pseudonym`() {
        val accountId = java.util.UUID.randomUUID()
        fun score(amount: String, expect: io.restassured.response.ValidatableResponse.() -> Unit) = Given {
            contentType("application/json")
            body(
                """{"amount":"$amount","currency":"EUR","rail":"SEPA","accountId":"$accountId",""" +
                    """"counterparty":{"scheme":"IBAN","value":"de89 3704 0044 0532 0130 00"},""" +
                    """"initiationMode":"CUSTOMER_PRESENT","channel":"WEB"}""",
            )
        } When {
            post("/api/v1/fraud/score")
        } Then {
            // Assertions must live INSIDE the Then block: the Kotlin extension validates the block,
            // and a .body(...) chained onto its return value was measured to pass against ALLOW.
            statusCode(200)
            expect()
        }

        score("15000.00") {
            body("verdict", equalTo("REVIEW"))
            body("reasons", hasItem("new-payee-high-amount"))
        }
        score("50.00") { body("verdict", equalTo("ALLOW")) }
        score("15000.00") { body("reasons", not(hasItem("new-payee-high-amount"))) }

        // Only the keyed pseudonym is stored; the IBAN appears nowhere in the row.
        val cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig()
        val stored = java.sql.DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use { conn ->
            conn.prepareStatement("SELECT payee_identifier FROM payee_history WHERE account_id = ?").use { ps ->
                ps.setObject(1, accountId)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
        assertThat(stored).singleElement().asString().matches("^cp2:test1:[0-9a-f]{64}$")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_API"])
    fun `POST score rejects a counterparty with an unknown scheme`() {
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":"EUR","rail":"SEPA","counterparty":{"scheme":"BIC","value":"X"}}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }
    }
}
