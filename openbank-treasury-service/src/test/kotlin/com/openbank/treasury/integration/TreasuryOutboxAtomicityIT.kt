// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
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
 * Issue #8353 — proves that `DealRepositoryImpl.save` commits the `deals` row, its new
 * `deal_transitions` row and the `treasury_outbox` event in **one** database transaction.
 *
 * ### Why presence is not the property
 *
 * [TreasuryDealApiIT] asserts that the outbox row exists after a deal is booked. That is necessary
 * and not sufficient: an implementation that stored the deal in one transaction and the event in a
 * second satisfies it while having lost the property — a crash between them leaves a booked deal
 * that no consumer ever hears about.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Booking
 * UPDATEs the `deals` row, INSERTs the `BOOKED` timeline row and INSERTs the outbox row; written by
 * one transaction, all three carry the same `xmin`. Two deals booked by two requests are the
 * known-different control.
 *
 * The scheduled dispatcher is switched off for this class: this service ships
 * `openbank.outbox.dispatch-enabled: true`, and a dispatcher claim UPDATEs the outbox row, which
 * would restamp its `xmin` and race the assertion.
 *
 * Ordered, with two identities: four-eyes needs an approver who is not the dealer, and
 * `@TestSecurity` fixes one identity per method.
 */
@QuarkusTest
@QuarkusTestResource(TreasuryOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryOutboxAtomicityIT {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("treasury-events-out") +
                mapOf("openbank.outbox.dispatch-enabled" to "false")

        override fun stop() = InMemoryConnector.clear()
    }

    // The service defaults tradeDate to the bank (Europe/Prague) day; the UTC date lags it by one
    // between 22:00 and 24:00 UTC in summer, and a valueDate before tradeDate is a 400.
    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    @Test
    @Order(1)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `1 - a dealer drafts and submits two deals, which announces nothing yet`() {
        firstDeal = draftAndSubmit("1100.00")
        secondDeal = draftAndSubmit("1200.00")
        // Arrangement assertion: the booking below must be the FIRST outbox write for these deals,
        // or the single-row claims in step 2 would be reading an earlier event.
        assertThat(writers(firstDeal)).isEmpty()
        assertThat(writers(secondDeal)).isEmpty()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "adam.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `2 - booking writes the deal row, its timeline row and the outbox event in one transaction`() {
        approve(firstDeal)
        approve(secondDeal)

        val first = writers(firstDeal)
        val second = writers(secondDeal)
        assertThat(first).describedAs("exactly one outbox row for deal %s", firstDeal).hasSize(1)
        assertThat(second).hasSize(1)

        val row = first.single()
        assertThat(row.eventType).isEqualTo("treasury.deal.booked.v1")
        assertThat(row.outboxXmin)
            .describedAs(
                "the deals row and its outbox row must carry the SAME Postgres xmin — different " +
                    "values mean two transactions wrote them, so one can commit without the other",
            )
            .isEqualTo(row.dealXmin)
        assertThat(row.transitionXmin)
            .describedAs("the BOOKED timeline row rides the same transaction")
            .isEqualTo(row.dealXmin)

        // Known-different control: two bookings are two transactions, so the identical comparison
        // must FAIL across them — otherwise the matches above would match everything.
        assertThat(second.single().outboxXmin)
            .describedAs("control: two separate bookings cannot share a writing transaction")
            .isNotEqualTo(row.outboxXmin)
    }

    /** Guards the `hasSize(1)` claims against a query that cannot return anything. */
    @Test
    @Order(3)
    fun `3 - the probe returns nothing for a deal that was never written`() {
        assertThat(writers(UUID.randomUUID().toString())).isEmpty()
    }

    private data class Writers(
        val dealXmin: String,
        val transitionXmin: String,
        val outboxXmin: String,
        val eventType: String,
    )

    private fun writers(dealId: String): List<Writers> = jdbc { connection ->
        connection.prepareStatement(
            """
            select d.xmin::text, t.xmin::text, o.xmin::text, o.event_type
            from deals d
            join treasury_outbox o on o.aggregate_id = d.deal_id
            join deal_transitions t on t.deal_id = d.deal_id and t.to_state = 'BOOKED'
            where d.deal_id = ?
            order by o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(dealId))
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { Writers(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
                    .toList()
            }
        }
    }

    private fun draftAndSubmit(principal: String): String {
        val id: String = given()
            .contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """
                {"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"CZK",
                 "principal":$principal,"rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}"}
                """.trimIndent(),
            )
            .`when`().post("/api/v1/treasury/deals")
            .then().statusCode(201)
            .extract().path("dealId")
        given()
            .contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .`when`().post("/api/v1/treasury/deals/$id/submit")
            .then().statusCode(200).body("state", equalTo("PENDING_APPROVAL"))
        return id
    }

    private fun approve(dealId: String) {
        given()
            .contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .`when`().post("/api/v1/treasury/deals/$dealId/approve")
            .then().statusCode(200).body("state", equalTo("BOOKED"))
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
        lateinit var firstDeal: String
        lateinit var secondDeal: String
    }
}
