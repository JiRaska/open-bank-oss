// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.e2e

import com.openbank.fraud.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * The fraud-analyst journey over the real HTTP surface: a risk maker recalibrates the threshold
 * set through the four-eyes proposal lane, and from the moment a different principal approves it,
 * the payment surface's verdicts move — an above-threshold payment is held for review and shows
 * up on the analyst review queue, a below-threshold one does not.
 *
 * Ordered, because the story is a sequence of states of one table. Each `@TestSecurity` identity
 * is a different principal — the maker/checker separation the four-eyes lane enforces (ADR-0347)
 * is itself under test.
 *
 * The integration tests in this module prove one property each (seed bytes, activation ordering,
 * privilege refusal, malformed-body 400); this proves the operator-visible route sequence serves
 * the whole calibration → scoring → review-queue story over HTTP, so a wiring regression that
 * deregisters a route (#3371), lets a proposed set leak into verdicts, or stops persisting REVIEW
 * verdicts into the queue fails here even when every property-level assertion stays green.
 *
 * Scoring is rules-only here (the ONNX shadow plane needs the glibc runtime image and no verdict
 * has ever depended on it — ADR-0139), and the review queue is the read-back surface: it is a
 * DIFFERENT route reading the same `fraud_scores` rows the score route wrote, so asserting the
 * flagged payment through it proves the write was durable, not just the response well-formed.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(FraudAnalystJourneyE2E.JourneyProfile::class)
class FraudAnalystJourneyE2E {

    /** Own boot, mirroring FraudThresholdActivationIT's isolation so the V9 seed is untouched. */
    class JourneyProfile : QuarkusTestProfile

    private var proposalId: String? = null

    @Test
    @Order(1)
    @TestSecurity(user = "risk-maker", roles = ["ROLE_RISK"])
    fun `1 - the maker proposes a recalibrated set and an identical retry replays it`() {
        val body = """
            {"version": 2, "thresholds": {
              "large-single-transaction": {"CZK": "1000", "EUR": "20000"},
              "velocity-h1-amount": {"CZK": "1000000", "EUR": "40000"},
              "new-payee-high-amount": {"CZK": "250000", "EUR": "10000"}}}
        """.trimIndent()

        proposalId = Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/fraud/thresholds/proposals")
        } Then {
            statusCode(201)
            body("state", equalTo("PROPOSED"))
            body("version", equalTo(2))
            body("proposedBy", equalTo("risk-maker"))
            body("id", notNullValue())
        } Extract { path("id") }

        // Identical retry of a still-pending proposal returns that proposal — no duplicate.
        Given {
            contentType("application/json")
            body(body)
        } When {
            post("/api/v1/fraud/thresholds/proposals")
        } Then {
            statusCode(201)
            body("id", equalTo(proposalId))
        }

        // The proposal is visible on the checker's pending lane.
        Given { this } When { get("/api/v1/fraud/thresholds/proposals/pending") } Then {
            statusCode(200)
            body("id", hasItem(proposalId))
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "risk-maker", roles = ["ROLE_RISK"])
    fun `2 - the maker cannot approve their own proposal and it changes no verdict`() {
        Given {
            contentType("application/json")
            body("""{"approve": true}""")
        } When {
            post("/api/v1/fraud/thresholds/proposals/$proposalId/decide")
        } Then {
            statusCode(422)
        }

        // Until a DIFFERENT principal decides it, the active set is still the migration seed.
        Given { this } When { get("/api/v1/fraud/thresholds/active") } Then {
            statusCode(200)
            body("version", equalTo(1))
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "risk-checker", roles = ["ROLE_COMPLIANCE"])
    fun `3 - a different principal approves and the set is in force`() {
        Given {
            contentType("application/json")
            body("""{"approve": true, "reason": "E2E calibration"}""")
        } When {
            post("/api/v1/fraud/thresholds/proposals/$proposalId/decide")
        } Then {
            statusCode(200)
            body("state", equalTo("EXECUTED"))
            body("decidedBy", equalTo("risk-checker"))
        }

        Given { this } When { get("/api/v1/fraud/thresholds/active") } Then {
            statusCode(200)
            body("version", equalTo(2))
            body("thresholds.large-single-transaction.CZK", equalTo("1000"))
        }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `4 - a below-threshold payment is allowed`() {
        Given {
            contentType("application/json")
            body("""{"amount":"999.99","currency":"CZK","rail":"SEPA_INSTANT"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
            body("verdict", equalTo("ALLOW"))
            // The engine's rule version is a constant of the rule code, not the threshold set.
            body("ruleVersion", equalTo("v4"))
        }
    }

    @Test
    @Order(5)
    @TestSecurity(user = "risk-analyst", roles = ["ROLE_COMPLIANCE"])
    fun `5 - the analyst queue is empty while nothing was flagged`() {
        // Read-back through the DIFFERENT route: the allowed payment never reached the queue.
        val queue = Given { this } When { get("/api/v1/fraud/review-queue") } Then {
            statusCode(200)
        } Extract { body().path<List<Map<String, Any>>>("") }
        assertThat(queue.map { (it["amount"] as Number).toFloat() }).doesNotContain(999.99f)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `6 - a payment at the threshold is held for review`() {
        Given {
            contentType("application/json")
            body("""{"amount":"1000.00","currency":"CZK","rail":"SEPA_INSTANT"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
            body("verdict", equalTo("REVIEW"))
            body("reasons", hasItem("large-single-transaction"))
            body("ruleVersion", equalTo("v4"))
        }
    }

    @Test
    @Order(7)
    @TestSecurity(user = "risk-analyst", roles = ["ROLE_COMPLIANCE"])
    fun `7 - the flagged payment is durably on the analyst review queue`() {
        // The queue row is the durable record, read back through its own route — not the
        // score response re-asserted. The amount is unique within this journey's database,
        // so it identifies the row the score route just wrote.
        Given { this } When { get("/api/v1/fraud/review-queue") } Then {
            statusCode(200)
            body("find { it.amount == 1000.0 && it.currency == 'CZK' }.verdict", equalTo("REVIEW"))
            body("find { it.amount == 1000.0 && it.currency == 'CZK' }.ruleVersion", equalTo("v4"))
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `8 - a second flag above the threshold is also held`() {
        Given {
            contentType("application/json")
            body("""{"amount":"2000.00","currency":"CZK","rail":"SEPA_INSTANT"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(200)
            body("verdict", equalTo("REVIEW"))
        }
    }

    @Test
    @Order(9)
    @TestSecurity(user = "risk-analyst", roles = ["ROLE_COMPLIANCE"])
    fun `9 - the queue holds every flagged payment, newest first, and only those`() {
        // This class owns its database (restrictToAnnotatedClass), so the queue holds exactly
        // the two REVIEW verdicts this journey wrote — nothing from other tests.
        val queue = Given { this } When { get("/api/v1/fraud/review-queue") } Then {
            statusCode(200)
        } Extract { body().path<List<Map<String, Any>>>("") }
        assertThat(queue.map { (it["amount"] as Number).toFloat() })
            .containsExactlyInAnyOrder(1000.00f, 2000.00f)
        assertThat(queue.map { it["verdict"] }).containsOnly("REVIEW")
        // Newest first (ADR-0230): the later, larger flag leads the queue.
        assertThat((queue.first()["amount"] as Number).toFloat()).isEqualTo(2000.00f)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "payment-surface", roles = ["ROLE_API"])
    fun `10 - the journey fails closed on a malformed scoring body`() {
        // Jackson coerces a non-string currency into a five-char string and Postgres would
        // raise value-too-long as a 500 — the resource must reject it at the boundary (400).
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":false,"rail":"SEPA"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }

        // A currency that is not exactly three upper-case letters is rejected without
        // touching the store ("XXX" passes the shape regex and is NOT a valid check here).
        Given {
            contentType("application/json")
            body("""{"amount":"10.00","currency":"USDT","rail":"SEPA"}""")
        } When {
            post("/api/v1/fraud/score")
        } Then {
            statusCode(400)
        }
    }
}
