// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.domain.model.DayCount
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Real HTTP, real Postgres, the ledger replaced by [FakeLedger] (which keeps the ledger's
 * idempotency contract). Ordered because four-eyes needs two different people, and
 * `@TestSecurity` fixes one identity per method (same shape as lending's LedgerBackfillIT).
 *
 * What only this test can show: the route is registered (#3371); the deal row, its timeline,
 * the journal reference and the outbox event commit together; nothing posts before BOOKED; and a
 * ledger response lost after commit leaves ONE journal after the retry.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryDealApiIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("treasury-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var ledger: FakeLedger

    // The service decides trade date and settlement/maturity eligibility on the Prague bank day
    // (AccountingClock.bank over Clock.systemUTC(), DefaultClockProducer) — derive "today" the same
    // way, or this drifts a day out of step with the service between 22:00/23:00 UTC and midnight.
    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun draftBody(principal: String, counterparty: String = "SIMBK-A", product: String = "MM_PLACEMENT") = """
        {"product":"$product","counterpartyId":"$counterparty","currency":"CZK",
         "principal":$principal,"rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}
    """.trimIndent()

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

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts and submits, the limit check is recorded and nothing posts`() {
        ledger.reset()
        dealId = draft(draftBody("100000.00"))
        action(dealId, "submit").then().statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.breached", equalTo(false))
        breachId = draft(draftBody("600000000.00", counterparty = "SIMBK-C"))
        action(breachId, "submit").then().statusCode(200).body("limitCheck.breached", equalTo(true))
        selfId = draft(draftBody("1000.00"))
        action(selfId, "submit").then().statusCode(200)
        assertThat(ledger.calls).isEmpty()
    }

    @Test
    @Order(10)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `10 - Idempotency-Key - required, a replay returns the same deal, reuse elsewhere is refused`() {
        given().contentType("application/json").body(draftBody("5.00"))
            .`when`().post("/api/v1/treasury/deals").then().statusCode(400)
        val key = UUID.randomUUID().toString()
        val first = draft(draftBody("7.00"), key)
        val replay = draft(draftBody("7.00"), key)
        assertThat(replay).isEqualTo(first)
        val submitKey = UUID.randomUUID().toString()
        action(first, "submit", key = submitKey).then().statusCode(200).body("state", equalTo("PENDING_APPROVAL"))
        action(first, "submit", key = submitKey).then().statusCode(200).body("state", equalTo("PENDING_APPROVAL"))
        action(first, "cancel", key = submitKey).then().statusCode(400)
        action(first, "cancel").then().statusCode(200).body("state", equalTo("CANCELLED"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"])
    fun `2 - four-eyes - the creator cannot approve their own deal, even holding the approver role`() {
        action(selfId, "approve").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
        assertThat(state(selfId)).isEqualTo("PENDING_APPROVAL")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "agent:treasury-drafter", roles = ["ROLE_TREASURY_APPROVER"])
    fun `3 - an AI agent principal can never approve or settle, whatever roles it carries`() {
        action(dealId, "approve").then().statusCode(403).body("error", equalTo("ACTOR_NOT_PERMITTED"))
        action(dealId, "settle").then().statusCode(403)
        action(dealId, "confirm").then().statusCode(403)
        assertThat(state(dealId)).isEqualTo("PENDING_APPROVAL")
        assertThat(ledger.calls).isEmpty()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `4 - a different approver books, a limit breach is refused, booking posts nothing`() {
        action(dealId, "approve").then().statusCode(200)
            .body("state", equalTo("BOOKED"))
            .body("approvedBy", equalTo("adam.approver"))
        action(breachId, "approve").then().statusCode(422).body("error", equalTo("LIMIT_BREACHED"))
        assertThat(
            ledger.calls,
        ).describedAs("nothing may post before BOOKED, and BOOKED itself posts nothing").isEmpty()
        assertThat(outboxTypes(UUID.fromString(dealId))).containsExactly("treasury.deal.booked.v1")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - a ledger response lost after commit - the deal stays CONFIRMED, the retry yields ONE journal`() {
        // ADR-0315 D2: a BOOKED deal does not settle until the counterparty's confirmation is recorded.
        action(dealId, "settle").then().statusCode(409).body("error", equalTo("INVALID_STATE"))
        action(dealId, "confirm").then().statusCode(200).body("state", equalTo("CONFIRMED"))
        assertThat(ledger.journals).describedAs("confirmation posts nothing").isEmpty()
        ledger.loseNextResponse = true
        action(dealId, "settle").then().statusCode(500)
        assertThat(state(dealId)).isEqualTo("CONFIRMED")
        assertThat(ledger.journals).hasSize(1)

        action(dealId, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
            .body("journals", hasSize<Any>(1))
            .body("journals[0].idempotencyKey", equalTo("treasury:$dealId:settled"))

        val key = "treasury:$dealId:settled"
        assertThat(ledger.calls.filter { it == key }).describedAs("posted twice under ONE key").hasSize(2)
        assertThat(ledger.journals).describedAs("but the ledger holds one journal").hasSize(1)
        val stored = journalIds(UUID.fromString(dealId))
        assertThat(stored).containsExactly(ledger.journals.getValue(key).first)
        assertThat(outboxTypes(UUID.fromString(dealId)))
            .containsExactly("treasury.deal.booked.v1", "treasury.deal.confirmed.v1", "treasury.deal.settled.v1")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - maturity before the maturity date is refused and posts nothing`() {
        action(dealId, "mature").then().statusCode(409)
        assertThat(ledger.journals.keys).containsExactly("treasury:$dealId:settled")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `7 - reversing the settled deal posts one offsetting journal`() {
        action(dealId, "reverse", """{"reason":"wrong value date"}""").then().statusCode(200)
            .body("state", equalTo("REVERSED"))
            .body("journals", hasSize<Any>(2))
        val reversal = ledger.journals.getValue("treasury:$dealId:reversed").second
        val settlement = ledger.journals.getValue("treasury:$dealId:settled").second
        assertThat(reversal.lines.map { it.glCode to it.side }).isEqualTo(
            settlement.lines.map { it.glCode to (if (it.side.name == "DEBIT") "CREDIT" else "DEBIT") }
                .map { (c, s) -> c to com.openbank.treasury.domain.model.Side.valueOf(s) },
        )
        assertThat(outboxTypes(UUID.fromString(dealId))).last().isEqualTo("treasury.deal.reversed.v1")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `8 - reads - blotter by state, the timeline, counterparties and positions`() {
        given().`when`().get("/api/v1/treasury/deals?state=REVERSED").then().statusCode(200)
            .body("dealId", org.hamcrest.Matchers.hasItem(dealId))
        given().`when`().get("/api/v1/treasury/deals/$dealId").then().statusCode(200)
            .body(
                "history.to",
                org.hamcrest.Matchers.contains(
                    "DRAFT",
                    "PENDING_APPROVAL",
                    "BOOKED",
                    "CONFIRMED",
                    "SETTLED",
                    "REVERSED",
                ),
            )
        given().`when`().get("/api/v1/treasury/counterparties").then().statusCode(200)
            .body("find { it.counterpartyId == 'CNB' }.kind", equalTo("CENTRAL_BANK"))
        given().`when`().get("/api/v1/treasury/positions?asOf=$today").then().statusCode(200)
            .body("positions.currency", org.hamcrest.Matchers.contains("CZK", "EUR"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `9 - a dealer cannot approve (RBAC) and an unknown deal is 404`() {
        action(selfId, "approve").then().statusCode(403)
        given().`when`().get("/api/v1/treasury/deals/${UUID.randomUUID()}").then().statusCode(404)
    }

    // --- ADR-0315 D4: senior override of a limit breach ---

    @Test
    @Order(11)
    @TestSecurity(user = "sara.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `11 - a senior records a limit override with a reason, which books nothing yet`() {
        action(breachId, "override-limit", """{"reason":""}""").then().statusCode(400)
        action(breachId, "override-limit", """{"reason":"ALCO-approved temporary excess"}""").then().statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitOverride.by", equalTo("sara.senior"))
            .body("limitOverride.reason", equalTo("ALCO-approved temporary excess"))
        action(breachId, "approve").then().statusCode(403) // a senior cannot book (RBAC)
        assertThat(state(breachId)).isEqualTo("PENDING_APPROVAL")
        given().`when`().get("/api/v1/treasury/limits/utilisation").then().statusCode(200)
            .body("limits.find { it.counterpartyId == 'SIMBK-C' && it.currency == 'CZK' }.breached", equalTo(true))
            .body(
                "limits.find { it.counterpartyId == 'SIMBK-C' && it.currency == 'CZK' }.activeOverrides",
                equalTo(1),
            )
            // Keyed per (counterparty, LIMIT currency): the override sits on SIMBK-C's CZK line only —
            // not on its EUR line, and not on another counterparty's CZK line (#10896).
            .body(
                "limits.find { it.counterpartyId == 'SIMBK-C' && it.currency == 'EUR' }.activeOverrides",
                equalTo(0),
            )
            .body(
                "limits.find { it.counterpartyId == 'SIMBK-A' && it.currency == 'CZK' }.activeOverrides",
                equalTo(0),
            )
    }

    @Test
    @Order(12)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `12 - an ordinary approver cannot override, but books the overridden breach`() {
        action(dealId, "override-limit", """{"reason":"x"}""").then().statusCode(403)
        // The override is read back from the database here, so this also proves V4 and the mapping.
        action(breachId, "approve").then().statusCode(200)
            .body("state", equalTo("BOOKED"))
            .body("limitOverride.by", equalTo("sara.senior"))
        // booking moves the deal off PENDING_APPROVAL: the override is no longer "active", but the
        // deal still consumes the limit (BOOKED is on-book), so utilisation stays breached.
        given().`when`().get("/api/v1/treasury/limits/utilisation").then().statusCode(200)
            .body("limits.find { it.counterpartyId == 'SIMBK-C' && it.currency == 'CZK' }.breached", equalTo(true))
            .body(
                "limits.find { it.counterpartyId == 'SIMBK-C' && it.currency == 'CZK' }.activeOverrides",
                equalTo(0),
            )
    }

    // --- #10896: ČNB lombard (marginal lending) facility ---

    private fun lombardBody(currency: String = "CZK", counterparty: String = "CNB", rate: String = "5.75") = """
        {"product":"CNB_LOMBARD","counterpartyId":"$counterparty","currency":"$currency",
         "principal":2500000.00,"rate":$rate,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}
    """.trimIndent()

    @Test
    @Order(13)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `13 - a dealer drafts an overnight ČNB lombard borrowing, off-product terms are 400`() {
        for (bad in listOf(
            lombardBody(currency = "EUR"),
            lombardBody(counterparty = "SIMBK-A"),
            lombardBody(rate = "0"),
        )) {
            given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .body(bad).`when`().post("/api/v1/treasury/deals").then().statusCode(400)
        }
        lombardId = draft(lombardBody())
        given().`when`().get("/api/v1/treasury/deals/$lombardId").then().statusCode(200)
            .body("product", equalTo("CNB_LOMBARD"))
            .body(
                "maturityDate",
                equalTo(com.openbank.treasury.domain.model.DayCount.nextBusinessDay(today).toString()),
            )
        action(lombardId, "submit").then().statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.breached", equalTo(false))
    }

    @Test
    @Order(14)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `14 - four-eyes books the lombard, settlement credits Borrowings from CNB 2320`() {
        action(lombardId, "approve").then().statusCode(200).body("state", equalTo("BOOKED"))
        action(lombardId, "confirm").then().statusCode(200).body("state", equalTo("CONFIRMED"))
        action(lombardId, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
        val settled = ledger.journals.getValue("treasury:$lombardId:settled").second
        assertThat(settled.lines.map { "${it.side} ${it.glCode} ${it.amount.toPlainString()} ${it.currency}" })
            .containsExactly("DEBIT 1001 2500000.00 CZK", "CREDIT 2320 2500000.00 CZK")
        assertThat(outboxTypes(UUID.fromString(lombardId)))
            .containsExactly("treasury.deal.booked.v1", "treasury.deal.confirmed.v1", "treasury.deal.settled.v1")
    }

    // --- #10896: FX spot ---

    /** The latest weekday on or before today: a valid FX trade AND value date the test can settle now. */
    private val businessDay: LocalDate = generateSequence(today) { it.minusDays(1) }.first { !DayCount.isWeekend(it) }

    private fun fxBody(buy: String = "EUR", sell: String = "CZK", extra: String = "") = """
        {"product":"FX_SPOT","counterpartyId":"SIMBK-A","buyCurrency":"$buy","sellCurrency":"$sell",
         "principal":1000.00,"rate":25.1$extra}
    """.trimIndent()

    @Test
    @Order(13)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `13 - a dealer drafts an FX spot, T+2 by default, limited on the CZK equivalent - bad shapes are 400`() {
        val defaulted = given().contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
            .body(fxBody(extra = ",\"tradeDate\":\"$businessDay\""))
            .`when`().post("/api/v1/treasury/deals")
            .then().statusCode(201)
            .body("product", equalTo("FX_SPOT"))
            .body("currency", equalTo("EUR"))
            .body("valueDate", equalTo(DayCount.spotDate(businessDay).toString()))
            .body("fx.side", equalTo("BUY"))
            .body("fx.sellCurrency", equalTo("CZK"))
            .body("fx.sellAmount", equalTo(25100.00f))
            .body("fx.rateFlag", org.hamcrest.Matchers.nullValue())
            .extract().path<String>("dealId")
        action(defaulted, "cancel").then().statusCode(200)

        fxId = draft(fxBody(extra = ",\"tradeDate\":\"$businessDay\",\"valueDate\":\"$businessDay\""))
        action(fxId, "submit").then().statusCode(200)
            .body("state", equalTo("PENDING_APPROVAL"))
            .body("limitCheck.currency", equalTo("CZK"))
            .body("limitCheck.exposureAfter", org.hamcrest.Matchers.greaterThanOrEqualTo(25100.0f))

        fun bad(body: String) = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString()).body(body)
            .`when`().post("/api/v1/treasury/deals").then().statusCode(400)
        bad(fxBody(buy = "CZK", sell = "CZK"))
        bad(fxBody(buy = "EUR", sell = "EUR"))
        bad("""{"product":"FX_SPOT","counterpartyId":"SIMBK-A","principal":1,"rate":25}""")
        bad(fxBody(extra = ",\"currency\":\"CZK\""))
        bad(fxBody(extra = ",\"tradeDate\":\"$businessDay\",\"valueDate\":\"${businessDay.plusDays(7)}\""))
        bad(draftBody("10.00").replace("}", ",\"buyCurrency\":\"EUR\"}"))
        bad("""{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK","principal":1,"rate":1}""")
        // #11041 review fix: a rate the store's NUMERIC(9,6) column can't hold 500s at flush without this.
        bad(
            """{"product":"FX_SPOT","counterpartyId":"SIMBK-A","buyCurrency":"EUR","sellCurrency":"CZK",
               "principal":1000.00,"rate":25000,"tradeDate":"$businessDay"}""",
        )
        // #11041 review fix: principal x rate rounding to 0.00 CZK violates fx_counter_amount > 0 at flush.
        bad(
            """{"product":"FX_SPOT","counterpartyId":"SIMBK-A","buyCurrency":"EUR","sellCurrency":"CZK",
               "principal":0.01,"rate":0.1,"tradeDate":"$businessDay"}""",
        )
    }

    @Test
    @Order(14)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `14 - a second person books the FX spot, settlement posts both legs once, it never matures`() {
        ledger.reset()
        action(fxId, "approve").then().statusCode(200).body("state", equalTo("BOOKED"))
        assertThat(ledger.calls).isEmpty()
        action(fxId, "confirm").then().statusCode(200).body("state", equalTo("CONFIRMED"))
        action(fxId, "settle").then().statusCode(200).body("state", equalTo("SETTLED"))
            .body("journals[0].idempotencyKey", equalTo("treasury:$fxId:settled"))
        val settlement = ledger.journals.getValue("treasury:$fxId:settled").second
        assertThat(settlement.lines.map { "${it.side} ${it.glCode} ${it.amount.toPlainString()} ${it.currency}" })
            .containsExactly(
                "DEBIT 1002 1000.00 EUR",
                "CREDIT 1991 1000.00 EUR",
                "DEBIT 1990 25100.00 CZK",
                "CREDIT 1001 25100.00 CZK",
            )
        action(fxId, "mature").then().statusCode(409)
        assertThat(ledger.calls).containsExactly("treasury:$fxId:settled")
        assertThat(outboxTypes(UUID.fromString(fxId)))
            .containsExactly("treasury.deal.booked.v1", "treasury.deal.confirmed.v1", "treasury.deal.settled.v1")
        // Read back from the row: proves V9's columns and the mapping.
        given().`when`().get("/api/v1/treasury/deals/$fxId").then().statusCode(200)
            .body("fx.buyAmount", equalTo(1000.00f))
            .body("maturityDate", equalTo(businessDay.toString()))
    }

    @Test
    @Order(15)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `15 - reversing the settled FX spot posts the flipped legs under its own key`() {
        action(fxId, "reverse", """{"reason":"booked the wrong side"}""").then().statusCode(200)
            .body("state", equalTo("REVERSED"))
        val settled = ledger.journals.getValue("treasury:$fxId:settled").second.lines
        val reversed = ledger.journals.getValue("treasury:$fxId:reversed").second.lines
        assertThat(reversed.map { it.glCode to it.side.name }).isEqualTo(
            settled.map { it.glCode to (if (it.side.name == "DEBIT") "CREDIT" else "DEBIT") },
        )
        val payload = jdbc { c ->
            c.prepareStatement(
                "select payload from treasury_outbox where aggregate_id = ? and event_type = 'treasury.deal.reversed.v1'",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(fxId))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }
        assertThat(payload).contains("\"product\":\"FX_SPOT\"").contains("\"fxSide\":\"BUY\"")
    }

    @Test
    @Order(16)
    fun `16 - the database refuses FX terms on a money-market row`() {
        val refused = runCatching {
            jdbc { c ->
                c.prepareStatement("update deals set fx_side = 'BUY', fx_counter_amount = 1 where deal_id = ?").use {
                    it.setObject(1, UUID.fromString(dealId))
                    it.executeUpdate()
                }
            }
        }
        assertThat(refused.exceptionOrNull()).hasMessageContaining("deals_fx_terms")
    }

    @Test
    @Order(17)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `17 - with the simulated market off there are no quotes (409), never an invented price`() {
        given().`when`().get("/api/v1/treasury/quotes?product=MM_PLACEMENT&currency=CZK&tenorDays=30")
            .then().statusCode(409).body("error", equalTo("INVALID_STATE"))
    }

    private fun state(id: String): String = jdbc { c ->
        c.prepareStatement("select state from deals where deal_id = ?").use { ps ->
            ps.setObject(1, UUID.fromString(id))
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun outboxTypes(id: UUID): List<String> = jdbc { c ->
        c.prepareStatement("select event_type from treasury_outbox where aggregate_id = ? order by id").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private fun journalIds(id: UUID): List<UUID> = jdbc { c ->
        c.prepareStatement("select journal_id from deal_journals where deal_id = ? and event = 'SETTLED'").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getObject(1, UUID::class.java) else null }.toList()
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
        lateinit var dealId: String
        lateinit var breachId: String
        lateinit var selfId: String
        lateinit var lombardId: String
        lateinit var fxId: String
    }
}
