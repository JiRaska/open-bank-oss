// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

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

    private val today: LocalDate = LocalDate.now()

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
    fun `5 - a ledger response lost after commit - the deal stays BOOKED, the retry yields ONE journal`() {
        ledger.loseNextResponse = true
        action(dealId, "settle").then().statusCode(500)
        assertThat(state(dealId)).isEqualTo("BOOKED")
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
            .containsExactly("treasury.deal.booked.v1", "treasury.deal.settled.v1")
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
                org.hamcrest.Matchers.contains("DRAFT", "PENDING_APPROVAL", "BOOKED", "SETTLED", "REVERSED"),
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
    }
}
