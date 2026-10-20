// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.integration

import com.openbank.fraud.domain.model.ScoreRequest
import com.openbank.fraud.domain.rules.FraudRuleEngine
import com.openbank.fraud.domain.rules.FraudThresholdFixtures
import com.openbank.fraud.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.DriverManager

/**
 * ADR-0347 end to end, over real HTTP against a real Postgres migrated by Flyway (#13437):
 *
 * (a) the `V9` seed reproduces, decision for decision, what the code constants decided;
 * (b) a proposed set changes no verdict — not even after its maker tries to approve it — until a
 *     DIFFERENT principal approves it, and then it does;
 * (c) with no active set the engine fails closed as REVIEW, and says why.
 *
 * Ordered, because (b) is a sequence of states of one table. `restrictToAnnotatedClass` gives this
 * class its own database, so the set it activates can never leak into another IT's verdicts.
 *
 * Requests carry no accountId, so no velocity/payee enrichment runs: the verdict is a function of
 * amount, currency and the threshold set alone, which is what makes the comparison against the
 * pure engine exact.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FraudThresholdActivationIT {

    @Test
    @Order(1)
    @TestSecurity(user = "risk-reader", roles = ["ROLE_RISK"])
    fun `the active set after migration is the seed, byte for byte`() {
        Given { this } When { get("/api/v1/fraud/thresholds/active") } Then {
            statusCode(200)
            body("version", equalTo(1))
            body("contentHash", equalTo(FraudThresholdFixtures.SEED_V1_HASH))
            body("thresholds.large-single-transaction.CZK", equalTo("500000"))
            body("thresholds.velocity-h1-amount.EUR", equalTo("40000"))
            body("thresholds.new-payee-high-amount.CZK", equalTo("250000"))
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `(a) seeded decisions equal what the constants decided`() {
        val cases = listOf(
            "499999.99" to "CZK",
            "500000" to "CZK",
            "1500000" to "CZK",
            "19999.99" to "EUR",
            "20000" to "EUR",
            "480000" to "EUR",
            "1.00" to "USD",
            "1250.00" to "CZK",
        )
        cases.forEach { (amount, ccy) ->
            val expected = FraudRuleEngine.score(
                ScoreRequest(amount = BigDecimal(amount), currency = ccy, rail = "SEPA_INSTANT"),
                FraudThresholdFixtures.SEED_V1,
            )
            score(amount, ccy).apply {
                assertThat(this["verdict"]).describedAs("$amount $ccy").isEqualTo(expected.verdict.name)
                assertThat(this["score"]).describedAs("$amount $ccy").isEqualTo(expected.score)
                assertThat(this["reasons"]).describedAs("$amount $ccy").isEqualTo(expected.reasons)
            }
        }
        assertThat(latestPersistedThresholdVersion()).isEqualTo(1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "risk-maker", roles = ["ROLE_RISK"])
    fun `(b1) the maker proposes v2 and it changes nothing`() {
        proposalId = Given {
            contentType("application/json")
            body(
                """
                {"version": 2, "thresholds": {
                  "large-single-transaction": {"CZK": "1000", "EUR": "20000"},
                  "velocity-h1-amount": {"CZK": "1000000", "EUR": "40000"},
                  "new-payee-high-amount": {"CZK": "250000", "EUR": "10000"}}}
                """.trimIndent(),
            )
        } When { post("/api/v1/fraud/thresholds/proposals") } Then {
            statusCode(201)
            body("state", equalTo("PROPOSED"))
            body("proposedBy", equalTo("risk-maker"))
        } Extract { path("id") }

        Given { this } When { get("/api/v1/fraud/thresholds/active") } Then { body("version", equalTo(1)) }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `(b2) a proposed set does not move a verdict`() {
        assertThat(score("1250.00", "CZK")["verdict"]).isEqualTo("ALLOW")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "risk-maker", roles = ["ROLE_RISK"])
    fun `(b3) the maker cannot approve it`() {
        Given {
            contentType("application/json")
            body("""{"approve": true}""")
        } When { post("/api/v1/fraud/thresholds/proposals/$proposalId/decide") } Then { statusCode(422) }

        Given { this } When { get("/api/v1/fraud/thresholds/proposals/pending") } Then {
            body("id", hasItem(proposalId))
        }
    }

    @Test
    @Order(6)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `(b4) still no effect after the refused self-approval`() {
        assertThat(score("1250.00", "CZK")["verdict"]).isEqualTo("ALLOW")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "risk-checker", roles = ["ROLE_COMPLIANCE"])
    fun `(b5) a different principal approves it`() {
        Given {
            contentType("application/json")
            body("""{"approve": true, "reason": "IT calibration"}""")
        } When { post("/api/v1/fraud/thresholds/proposals/$proposalId/decide") } Then {
            statusCode(200)
            body("state", equalTo("EXECUTED"))
            body("decidedBy", equalTo("risk-checker"))
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `(b6) now the approved set decides`() {
        val result = score("1250.00", "CZK")
        assertThat(result["verdict"]).isEqualTo("REVIEW")
        assertThat(result["reasons"] as List<*>).contains("large-single-transaction")
        assertThat(latestPersistedThresholdVersion()).isEqualTo(2)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "risk-reader", roles = ["ROLE_RISK"])
    fun `a higher version stays in force over a lower one whatever their activeFrom order`() {
        // v3 written directly with an activeFrom EARLIER than v2's: the active set must be the
        // higher version, so a lower version scheduled for a later date can never reactivate.
        jdbc {
            it.createStatement().executeUpdate(
                """
                INSERT INTO fraud_threshold_set (id, version, state, thresholds_json, content_hash, active_from,
                    proposed_by, proposed_at, decided_by, decided_at)
                SELECT '01920000-0000-7000-8000-0000000003a3', 3, 'EXECUTED', thresholds_json, content_hash,
                    now() - interval '2 hours', 'it-maker', now(), 'it-checker', now()
                FROM fraud_threshold_set WHERE version = 1
                """.trimIndent(),
            )
        }
        Given { this } When { get("/api/v1/fraud/thresholds/active") } Then { body("version", equalTo(3)) }
    }

    @Test
    @Order(10)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `(c) with no active set scoring fails closed as REVIEW and names the cause`() {
        // Simulates the state no deployment should reach (V9 seeds v1): nothing EXECUTED. The
        // CHECK constraint forbids EXECUTED without active_from, so retire both rows honestly.
        jdbc {
            it.createStatement().executeUpdate("UPDATE fraud_threshold_set SET state = 'WITHDRAWN', active_from = NULL")
        }

        val result = score("1.00", "CZK")
        assertThat(result["verdict"]).isEqualTo("REVIEW")
        assertThat(result["reasons"] as List<*>).contains(FraudRuleEngine.THRESHOLDS_UNAVAILABLE)
        assertThat(latestPersistedThresholdVersion()).isNull()
    }

    private fun score(amount: String, currency: String): Map<String, Any?> = Given {
        contentType("application/json")
        body("""{"amount": "$amount", "currency": "$currency", "rail": "SEPA_INSTANT"}""")
    } When { post("/api/v1/fraud/score") } Then { statusCode(200) } Extract { jsonPath().getMap("") }

    private fun latestPersistedThresholdVersion(): Int? = jdbc { conn ->
        conn.createStatement().executeQuery(
            "SELECT threshold_set_version FROM fraud_scores ORDER BY created_at DESC, id DESC LIMIT 1",
        ).use { rs ->
            check(rs.next()) { "no fraud_scores row was written" }
            rs.getInt(1).takeUnless { rs.wasNull() }
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private companion object {
        // Shared across the ordered methods: JUnit makes a new instance per method.
        lateinit var proposalId: String
    }
}
