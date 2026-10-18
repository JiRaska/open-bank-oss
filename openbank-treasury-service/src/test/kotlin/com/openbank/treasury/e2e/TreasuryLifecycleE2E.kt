// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.e2e

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journey of a money-market deal from the synthetic quote board to reversal, over the
 * real HTTP surface against real Postgres. The treasury service is ADR-0315's reference
 * implementation, and its API ITs prove each transition in isolation — this suite proves the
 * **spine as one continuous story** across two different people, which is what the collector's
 * E2E row counts:
 *
 *  - **Price discovery to draft**: the dealer reads the synthetic bid/ask board off the risk
 *    engine's latest curve set (a missing parameter is 400, never an invented price) and drafts a
 *    placement AT the counterparty's quote.
 *  - **Four-eyes booking (ADR-0315)**: a different person approves — the deal is BOOKED under
 *    `approvedBy` — and the ADR-0315 D2 gate holds over real HTTP: a BOOKED deal cannot settle
 *    until the counterparty's confirmation is recorded (409 INVALID_STATE), confirmation posts
 *    nothing, and settlement posts exactly one journal under `treasury:{id}:settled`.
 *  - **The lifecycle is a state machine**: maturity before the maturity date is refused 409,
 *    reversal posts the offsetting journal and the deal's own timeline reads back the exact chain
 *    DRAFT → PENDING_APPROVAL → BOOKED → CONFIRMED → SETTLED → REVERSED.
 *  - **Least privilege**: a dealer is refused 403 on approve (RBAC), and a viewer is refused 403
 *    on the blotter entirely.
 *
 * The simulated market and its quotes are switched on by the profile (literals only — a profile
 * loads in a different classloader from the test). The risk-engine and ledger wires are the
 * classpath fakes ([com.openbank.treasury.integration.FakeCurveSets], FakeLedger) — their real
 * pacts are verified in the contract suite; no broker is claimed (outgoing Kafka in-memory).
 */
@QuarkusTest
@TestProfile(TreasuryLifecycleE2E.QuotesOn::class)
@QuarkusTestResource(TreasuryLifecycleE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryLifecycleE2E {

    /** Literal values only: a profile loads in a different classloader from the test (root CLAUDE.md). */
    class QuotesOn : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.treasury.simulated-market.enabled" to "true",
            "openbank.treasury.simulated-market.quotes.enabled" to "true",
            "openbank.treasury.simulated-market.interval" to "1s",
            "openbank.treasury.simulated-market.initial-delay" to "1s",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("treasury-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    // The service decides value/maturity eligibility on the Prague bank day — derive "today" the
    // same way or this drifts a day out of step between 22:00/23:00 UTC and midnight.
    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    companion object {
        // Quarkus tests run one instance per method: the deal id must live on the companion.
        lateinit var dealId: String
    }

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - the dealer reads the synthetic board and drafts a placement at the quote`() {
        given().`when`()
            .get("/api/v1/treasury/quotes?product=MM_PLACEMENT&currency=CZK&tenorDays=30")
            .then().statusCode(200)
            .body("synthetic", equalTo(true))
            .body("quotes.counterpartyId", contains("SIMBK-A", "SIMBK-B", "SIMBK-C"))
            // Flat 3.5 % curve, 30 days: mid 3.4570 %, SIMBK-A (5 bp) bid 3.4070 / ask 3.5070;
            // the deal rate of a PLACEMENT is the counterparty's BID.
            .body("quotes[0].dealRate", equalTo(3.4070f))
        // A missing parameter is a client error — never a silently invented price.
        given().`when`().get("/api/v1/treasury/quotes?currency=CZK&tenorDays=30")
            .then().statusCode(400)

        dealId = draft(
            """
            {"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK",
             "principal":100000.00,"rate":3.4070,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}
            """.trimIndent(),
        )
        action(dealId, "submit").then().statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.breached", equalTo(false))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - a different person books and the D2 gate holds settle until confirmation`() {
        action(dealId, "approve").then().statusCode(200)
            .body("state", equalTo("BOOKED"))
            .body("approvedBy", equalTo("adam.approver"))

        // ADR-0315 D2: a BOOKED deal does not settle until the counterparty's confirmation exists.
        action(dealId, "settle").then().statusCode(409).body("error", equalTo("INVALID_STATE"))
        action(dealId, "confirm").then().statusCode(200).body("state", equalTo("CONFIRMED"))
        action(dealId, "settle").then().statusCode(200)
            .body("state", equalTo("SETTLED"))
            .body("journals", hasSize<Any>(1))
            .body("journals[0].idempotencyKey", equalTo("treasury:$dealId:settled"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `3 - maturity is date gated and reversal closes the deal with an offsetting journal`() {
        // The deal matures in 30 days, not today.
        action(dealId, "mature").then().statusCode(409)

        action(dealId, "reverse", """{"reason":"wrong value date"}""").then().statusCode(200)
            .body("state", equalTo("REVERSED"))
            .body("journals", hasSize<Any>(2))

        // The deal's own timeline is the audit record of the whole spine.
        given().`when`().get("/api/v1/treasury/deals/$dealId").then().statusCode(200)
            .body(
                "history.to",
                contains("DRAFT", "PENDING_APPROVAL", "BOOKED", "CONFIRMED", "SETTLED", "REVERSED"),
            )
        given().`when`().get("/api/v1/treasury/deals?state=REVERSED").then().statusCode(200)
            .body("dealId", hasItem(dealId))
        // REVERSED deals count into neither actual nor projected positions.
        given().`when`().get("/api/v1/treasury/positions?asOf=$today").then().statusCode(200)
            .body("basis", equalTo("ACTUAL"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `4 - a dealer is refused 403 on approve`() {
        val id = draft(
            """
            {"product":"MM_PLACEMENT","counterpartyId":"SIMBK-B","currency":"CZK",
             "principal":50000.00,"rate":3.40,"valueDate":"$today","maturityDate":"${today.plusDays(7)}"}
            """.trimIndent(),
        )
        action(id, "submit").then().statusCode(200)
        action(id, "approve").then().statusCode(403)
        action(id, "cancel").then().statusCode(200).body("state", equalTo("CANCELLED"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "vera.viewer", roles = ["ROLE_VIEWER"])
    fun `5 - a viewer is refused 403 on the blotter`() {
        given().`when`().get("/api/v1/treasury/deals").then().statusCode(403)
    }

    // ---------- REST journey helpers ----------

    private fun draft(body: String, key: String = UUID.randomUUID().toString()): String = given()
        .contentType("application/json").header("Idempotency-Key", key).body(body)
        .`when`().post("/api/v1/treasury/deals")
        .then().statusCode(201).body("state", equalTo("DRAFT")).body("dayCount", equalTo("ACT/360"))
        .extract().path("dealId")

    private fun action(id: String, verb: String, body: String? = null, key: String = UUID.randomUUID().toString()) =
        given()
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .apply { if (body != null) body(body) }
            .`when`().post("/api/v1/treasury/deals/$id/$verb")
}
