// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.integration

import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `StandingOrderRepositoryImpl.saveWithExecution` commits the
 * `standing_orders` row and its `standing_order_outbox` row in **one** database transaction, so
 * neither can exist without the other.
 *
 * The subject is the FAILED transition: `StandingOrderService.recordFailure` counts consecutive
 * failures and, on the third (`StandingOrder.MAX_CONSECUTIVE_FAILURES`), flips the order to FAILED
 * *and* emits `standing-order.failed.v1`. That is the only one of this service's two
 * `saveWithExecution` call sites reachable over HTTP — the other is the scheduled execution sweep
 * — and it is the one that matters: it is what tells the rest of the fleet a recurring payment has
 * stopped being attempted.
 *
 * ### Why presence is not the property
 *
 * The house pattern drives the flow through the real REST endpoint and asserts the outbox row
 * landed. That is necessary — a mocked repository commits nothing, and a reactive Panache repo
 * cannot be called from a bare `@QuarkusTest` thread ("No current Vertx context found"), so only a
 * real HTTP request can exercise the write — but it is **not sufficient**: an implementation that
 * persisted the aggregate in one transaction and the outbox row in a second would satisfy every
 * presence assertion while having lost the property. The sibling `StandingOrderOutboxClaimIT`
 * seeds outbox rows directly and tests claim/dispatch semantics, so it is silent about the write.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Splitting `saveWithExecution`'s single `Panache.withTransaction` in two turns this test red,
 * where a presence assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`. This module's `%test` profile already sets
 * `openbank.outbox.dispatch-enabled: false` (#7539, for the same race), but the property this test
 * depends on is stated here rather than inherited — a later profile edit must not silently make
 * the assertion read a dispatcher-rewritten row. The daily execution cron is separately disabled.
 */
@QuarkusTest
@QuarkusTestResource(StandingOrderOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class StandingOrderOutboxAtomicityIT {

    class NoDispatchInMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("standing-order-events-out") +
                InMemoryConnector.switchIncomingChannelsToInMemory("standing-order-due-in") +
                mapOf(
                    "openbank.outbox.dispatch-enabled" to "false",
                    "openbank.scheduler.execution-enabled" to "false",
                )

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `the FAILED transition commits the order row and its outbox row in one transaction`() {
        val orderId = createOrder()

        recordFailure(orderId, expectedStatus = "ACTIVE")
        recordFailure(orderId, expectedStatus = "ACTIVE")
        // Known-different control, captured BEFORE the transition that emits the event: this row
        // version was written by the second recordFailure's own transaction, which wrote no outbox
        // row at all. The final outbox row must NOT match it — otherwise the comparison below
        // would be matching everything and could not fail.
        val xminAfterSecondFailure = orderXmin(orderId)

        recordFailure(orderId, expectedStatus = "FAILED")

        val rows = writersOf(orderId)
        assertThat(rows)
            .describedAs("exactly one standing_order_outbox row for order %s", orderId)
            .hasSize(1)
        val (orderXmin, outboxXmin, eventType) = rows.single()
        assertThat(eventType).isEqualTo(EVENT_FAILED)
        assertThat(outboxXmin)
            .describedAs(
                "the standing_orders row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (order xmin=%s, outbox xmin=%s)",
                orderXmin,
                outboxXmin,
            )
            .isEqualTo(orderXmin)
        assertThat(outboxXmin)
            .describedAs(
                "control: the row version left by the SECOND failure was written by a different " +
                    "transaction, so the outbox row must not match it (order xmin after failure " +
                    "2=%s, outbox xmin=%s)",
                xminAfterSecondFailure,
                outboxXmin,
            )
            .isNotEqualTo(xminAfterSecondFailure)
    }

    /**
     * Guards the assertion above against reading its own success from an empty set: an order id
     * that was never written must produce no pair at all, so `hasSize(1)` is a claim the query is
     * capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for an order that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val orderXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(orderId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT s.xmin::text AS order_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM standing_orders s
            JOIN standing_order_outbox o ON o.aggregate_id = s.id
            WHERE s.id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, orderId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    private fun orderXmin(orderId: UUID): String = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT xmin::text FROM standing_orders WHERE id = ?").use { statement ->
            statement.setObject(1, orderId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "no standing_orders row for $orderId" }
                rows.getString(1)
            }
        }
    }

    private fun createOrder(): UUID {
        val created = RestAssured.given()
            .contentType("application/json")
            .body(
                """
                {
                  "idempotencyKey": "atomicity-it-${UUID.randomUUID()}",
                  "partyId": "${UUID.randomUUID()}",
                  "debitAccountId": "${UUID.randomUUID()}",
                  "debtorIban": "DE89370400440532013000",
                  "debtorName": "Alice Debtor",
                  "creditorIban": "DE75512108001245126199",
                  "creditorName": "Brno Utility",
                  "creditorBic": null,
                  "amountMinorUnits": 123456,
                  "currency": "EUR",
                  "frequency": "MONTHLY",
                  "paymentType": "SEPA_CREDIT",
                  "remittanceInfo": "Monthly settlement",
                  "startDate": "${LocalDate.now()}",
                  "endDate": null
                }
                """.trimIndent(),
            )
            .post("/api/v1/standing-orders")
        assertThat(created.statusCode)
            .describedAs("POST /api/v1/standing-orders: %s", created.body.asString())
            .isEqualTo(201)
        return UUID.fromString(created.jsonPath().getString("id"))
    }

    private fun recordFailure(orderId: UUID, expectedStatus: String) {
        val response = RestAssured.given()
            .contentType("application/json")
            .patch("/api/v1/standing-orders/$orderId/record-failure")
        assertThat(response.statusCode)
            .describedAs("PATCH record-failure: %s", response.body.asString())
            .isEqualTo(200)
        // Arrangement assertion: a change that alters how many failures reach FAILED must fail
        // here rather than silently move this test onto a write path that emits no outbox row.
        assertThat(response.jsonPath().getString("status")).isEqualTo(expectedStatus)
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `StandingOrderService.EVENT_STANDING_ORDER_FAILED` — the wire value is the subject. */
        const val EVENT_FAILED = "standing-order.failed.v1"
    }
}
