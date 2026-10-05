// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315 D2 over real HTTP and a real Postgres: `POST /deals/{id}/confirm` is a registered route,
 * the CONFIRMED row passes the V13 state constraint, the confirmed event commits in the same
 * transaction as the state change, and every principal that must not confirm is refused — the
 * deal's own dealer (even holding the approver role), an AI agent (even holding it), and a dealer
 * without it. `openbank.treasury.confirmation.required` is its default (true) here.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryDealConfirmationIT {

    @jakarta.inject.Inject
    lateinit var fundingLedger: FakeLedgerRead

    @org.junit.jupiter.api.BeforeEach
    fun fundTestLedger() {
        fundingLedger.reset()
        fundingLedger.defaultBalance = java.math.BigDecimal("1000000000000.00")
    }

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun action(id: String, verb: String, body: String? = null, key: String = UUID.randomUUID().toString()) =
        given()
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .apply { if (body != null) body(body) }
            .`when`().post("/api/v1/treasury/deals/$id/$verb")

    @Test
    @Order(1)
    @TestSecurity(user = "cora.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts and submits`() {
        dealId = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-B","currency":"CZK","principal":42000.00,
                   "rate":4.10,"valueDate":"$today","maturityDate":"${today.plusDays(14)}"}""",
            )
            .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")
        action(dealId, "submit").then().statusCode(200).body("state", equalTo("PENDING_APPROVAL"))
        action(dealId, "confirm").then().statusCode(403) // RBAC: a dealer is not the back office
    }

    @Test
    @Order(2)
    @TestSecurity(user = "bob.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - confirm before booking is a 409, then a second person books`() {
        action(dealId, "confirm").then().statusCode(409).body("error", equalTo("INVALID_STATE"))
        action(dealId, "approve").then().statusCode(200).body("state", equalTo("BOOKED"))
    }

    @Test
    @Order(3)
    @TestSecurity(user = "cora.dealer", roles = ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"])
    fun `3 - the deal's own dealer cannot confirm it, even holding the approver role`() {
        action(dealId, "confirm").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
        assertThat(state()).isEqualTo("BOOKED")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "agent:treasury-dealing-assistant", roles = ["ROLE_TREASURY_APPROVER"])
    fun `4 - an AI agent can never confirm, whatever roles it carries`() {
        action(dealId, "confirm").then().statusCode(403).body("error", equalTo("ACTOR_NOT_PERMITTED"))
        assertThat(state()).isEqualTo("BOOKED")
        assertThat(outboxTypes()).containsExactly("treasury.deal.booked.v1")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "bob.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - settlement of a BOOKED deal is refused, the back office confirms, a replay is idempotent`() {
        action(dealId, "settle").then().statusCode(409).body("message", org.hamcrest.Matchers.containsString("confirm"))
        // Money-path idempotency: no Idempotency-Key is a 400, not a 500 (nullable header, #8351).
        given().contentType("application/json").`when`().post("/api/v1/treasury/deals/$dealId/confirm")
            .then().statusCode(400)
        val key = UUID.randomUUID().toString()
        action(dealId, "confirm", """{"reference":"SWIFT-MT320-0042"}""", key).then().statusCode(200)
            .body("state", equalTo("CONFIRMED"))
            .body("history[-1].actor", equalTo("bob.approver"))
            .body("history[-1].note", equalTo("counterparty confirmation received: SWIFT-MT320-0042"))
        action(dealId, "confirm", """{"reference":"SWIFT-MT320-0042"}""", key).then().statusCode(200)
            .body("state", equalTo("CONFIRMED"))
        // Row, timeline and outbox committed together; the replay added no second event.
        assertThat(state()).isEqualTo("CONFIRMED")
        assertThat(outboxTypes()).containsExactly("treasury.deal.booked.v1", "treasury.deal.confirmed.v1")
        assertThat(confirmedPayload()).contains("\"confirmedBy\":\"bob.approver\"").contains("\"simulated\":false")
        action(dealId, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
        assertThat(outboxTypes()).containsExactly(
            "treasury.deal.booked.v1",
            "treasury.deal.confirmed.v1",
            "treasury.deal.settled.v1",
        )
    }

    @Test
    @Order(6)
    fun `6 - the state constraint still refuses a value outside the lifecycle`() {
        val refused = runCatching {
            jdbc { c ->
                c.prepareStatement("update deals set state = 'AFFIRMED' where deal_id = ?").use {
                    it.setObject(1, UUID.fromString(dealId))
                    it.executeUpdate()
                }
            }
        }
        assertThat(refused.exceptionOrNull()).hasMessageContaining("deals_state_check")
    }

    private fun state(): String = jdbc { c ->
        c.prepareStatement("select state from deals where deal_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(dealId))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun outboxTypes(): List<String> = jdbc { c ->
        c.prepareStatement("select event_type from treasury_outbox where aggregate_id = ? order by id").use { ps ->
            ps.setObject(1, UUID.fromString(dealId))
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private fun confirmedPayload(): String = jdbc { c ->
        c.prepareStatement(
            "select payload from treasury_outbox where aggregate_id = ? and event_type = 'treasury.deal.confirmed.v1'",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(dealId))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
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

    companion object {
        private lateinit var dealId: String
    }
}
