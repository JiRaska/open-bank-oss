// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.e2e

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.treasury.integration.FakeLedger
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The treasury limit-override money journey over the real HTTP surface: a dealer drafts a deal
 * whose notional breaches the counterparty limit, submission parks it PENDING_APPROVAL with the
 * breach recorded, a plain approver is refused (LIMIT_BREACHED — the money does not move), a
 * senior overrider carrying a reason lifts the breach, a different approver books, the back
 * office confirms, and settlement posts exactly one journal that the read route reports.
 *
 * Ordered, because the lifecycle is a state machine — each step is only reachable from the
 * previous one, and `@TestSecurity` fixes one identity per method.
 *
 * The module's ITs prove one property each (four-eyes self-approval, lost ledger responses,
 * confirmation idempotency, override eligibility); this proves the connected operator journey
 * across the override path — a wiring regression that deregisters a route (#3371), books past
 * an un-overridden breach, or settles before confirmation fails here even when every
 * property-level assertion stays green.
 *
 * The ledger is the module's established [FakeLedger] (it keeps the ledger's own idempotency
 * contract, so a settlement posting asserts the real journal key); Kafka channels are in-memory;
 * everything else — limit measurement, four-eyes, state machine, persistence, outbox — is the
 * real, CDI-wired code.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@QuarkusTestResource(TreasuryLimitOverrideE2E.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(TreasuryLimitOverrideE2E.JourneyProfile::class)
class TreasuryLimitOverrideE2E {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("treasury-events-out", "treasury-payments-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("institution-transfer-status-in")

        override fun stop() = InMemoryConnector.clear()
    }

    /** Own boot (own container DB and a fresh FakeLedger), mirroring the ITs' isolation. */
    class JourneyProfile : QuarkusTestProfile

    @Inject
    lateinit var ledger: FakeLedger

    // The service decides trade/settlement eligibility on the Prague bank day — derive "today"
    // the same way or the test drifts a day out of step around midnight local.
    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private var dealId: String = ""

    @Test
    @Order(1)
    @TestSecurity(user = "nora.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts a deal beyond the counterparty limit`() {
        val response = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """
                {"product":"MM_PLACEMENT","counterpartyId":"SIMBK-C","currency":"CZK",
                 "principal":600000000.00,"rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}
                """.trimIndent(),
            )
            .post("/api/v1/treasury/deals").then()
            .statusCode(201)
            .body("dealId", notNullValue())
            .body("state", equalTo("DRAFT"))
            .extract()
        dealId = response.path("dealId")

        // The deal reads back through its own route with the drafted economics intact.
        val readBack = given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("dealId", equalTo(dealId))
            .body("state", equalTo("DRAFT"))
            .body("counterpartyId", equalTo("SIMBK-C"))
            .extract()
        // The parser's number type is an implementation detail; the wire value is the contract.
        assertThat(BigDecimal(readBack.path<Number>("principal").toString()))
            .isEqualByComparingTo("600000000")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "nora.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `2 - submission parks the deal with the breach recorded, nothing posted`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/treasury/deals/$dealId/submit").then()
            .statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.breached", equalTo(true))

        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.breached", equalTo(true))
        assertThat(ledger.calls).describedAs("nothing posts before settlement").isEmpty()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "pavel.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `3 - a plain approver is refused past the un-overridden breach`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/treasury/deals/$dealId/approve").then()
            .statusCode(422)
            .body("error", equalTo("LIMIT_BREACHED"))

        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
        assertThat(ledger.calls).isEmpty()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "nora.dealer", roles = ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `4 - the deal's own dealer cannot override the limit either`() {
        // Four-eyes on the override itself: carrying the senior role changes nothing, the
        // creator is refused with the same 422 as any other four-eyes violation.
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"reason":"relationship manager committed the line"}""")
            .post("/api/v1/treasury/deals/$dealId/override-limit").then()
            .statusCode(422)

        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("limitCheck.breached", equalTo(true))
            .body("limitOverride", equalTo(null))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "senior.petrova", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `5 - a different senior overrides the breach with a recorded reason`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"reason":"relationship manager committed the line"}""")
            .post("/api/v1/treasury/deals/$dealId/override-limit").then()
            .statusCode(200)

        // The override is visible on the read route. `breached` stays true — it is derived
        // from the measured exposure, which the override does not deny; what changes is the
        // recorded override carrying actor, reason and the exposure it covers.
        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.breached", equalTo(true))
            .body("limitOverride.by", equalTo("senior.petrova"))
            .body("limitOverride.reason", equalTo("relationship manager committed the line"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "pavel.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - a different approver books, booking still posts nothing`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/treasury/deals/$dealId/approve").then()
            .statusCode(200)
            .body("state", equalTo("BOOKED"))
            .body("approvedBy", equalTo("pavel.approver"))

        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("state", equalTo("BOOKED"))
        assertThat(ledger.calls)
            .describedAs("BOOKED itself posts nothing; settlement posts the journal").isEmpty()
    }

    @Test
    @Order(7)
    @TestSecurity(user = "pavel.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `7 - settlement before the counterparty confirmation is refused, then confirmed`() {
        // ADR-0315 D2: a BOOKED deal does not settle until the confirmation is recorded.
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/treasury/deals/$dealId/settle").then()
            .statusCode(409)
            .body("error", equalTo("INVALID_STATE"))

        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body("""{"reference":"SWIFT-MT320-9917"}""")
            .post("/api/v1/treasury/deals/$dealId/confirm").then()
            .statusCode(200)
            .body("state", equalTo("CONFIRMED"))

        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("state", equalTo("CONFIRMED"))
            .body("history[-1].note", equalTo("counterparty confirmation received: SWIFT-MT320-9917"))
        assertThat(ledger.journals).describedAs("confirmation posts nothing").isEmpty()
    }

    @Test
    @Order(8)
    @TestSecurity(user = "pavel.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `8 - settlement posts exactly one journal and reads back SETTLED`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .post("/api/v1/treasury/deals/$dealId/settle").then()
            .statusCode(200)
            .body("state", equalTo("SETTLED"))
            .body("journals", hasSize<Any>(1))
            .body("journals[0].idempotencyKey", equalTo("treasury:$dealId:settled"))

        // The read route — not the settle response — is the observable the operator keeps.
        given().get("/api/v1/treasury/deals/$dealId").then()
            .statusCode(200)
            .body("state", equalTo("SETTLED"))
            .body("journals[0].idempotencyKey", equalTo("treasury:$dealId:settled"))
        assertThat(ledger.journals.keys).containsExactly("treasury:$dealId:settled")
    }

    @Test
    @Order(9)
    @TestSecurity(user = "backoffice.kovar", roles = ["ROLE_TREASURY_VIEWER"])
    fun `9 - the fail-closed edge - a viewer cannot draft`() {
        given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """
                {"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK",
                 "principal":1000.00,"rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(7)}"}
                """.trimIndent(),
            )
            .post("/api/v1/treasury/deals").then()
            .statusCode(403)
    }

    @Test
    @Order(10)
    @TestSecurity(user = "backoffice.kovar", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `10 - the read surface fails closed on malformed and unknown ids`() {
        // Unlike settlement's canonical-UUID parser, this route answers one code for every
        // unreadable id — nothing on this surface leaks whether a malformed id ever parsed.
        given().get("/api/v1/treasury/deals/not-a-uuid").then().statusCode(404)
        given().get("/api/v1/treasury/deals/${UUID.randomUUID()}").then().statusCode(404)
    }
}
