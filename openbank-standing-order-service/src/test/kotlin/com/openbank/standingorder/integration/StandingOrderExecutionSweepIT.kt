// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.standingorder.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.standingorder.domain.model.Frequency
import com.openbank.standingorder.domain.model.PaymentType
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.domain.model.StandingOrderStatus
import com.openbank.standingorder.infrastructure.persistence.repository.StandingOrderOutboxRepositoryImpl
import com.openbank.standingorder.infrastructure.persistence.repository.StandingOrderRepositoryImpl
import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Regression coverage for #2148 — the daily execution sweep never executed a single order.
 *
 * `StandingOrderExecutionScheduler.sweep()` was a plain `fun sweep(): Unit = runBlocking { … }`.
 * Quarkus invokes a plain `@Scheduled` method on a bare `executor-thread`, which carries no Vert.x
 * context, so the first reactive Panache query inside (`findDueForExecution`) threw
 * `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread` — before the
 * per-order try/catch — and every sweep aborted with **zero** outbox rows written.
 *
 * **Why this test drives the cron and not `sweep()` directly.** The defect is in *how the framework
 * invokes the method*, so calling `sweep()` from the test would supply a context the scheduler does
 * not, and prove nothing. (That is also why the mocked-repo `StandingOrderServiceTest` never saw it:
 * no real reactive session, so the threading never runs.) The profile below shrinks the cron to
 * every two seconds so a genuinely scheduler-dispatched sweep runs against a real Postgres; the
 * test seeds one due order and waits for its atomic `standing-order.due.v1` outbox row. Reverting
 * the fix to `runBlocking` makes this time out with 0 rows and HR000068 in the log.
 *
 * All assertions are scoped to this test's own order id, so ACTIVE due orders other IT classes may
 * leave behind in the shared Postgres cannot affect the result.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(StandingOrderExecutionSweepIT.FastSweepProfile::class)
class StandingOrderExecutionSweepIT {

    /**
     * Fires the execution sweep every two seconds instead of at 03:00, and keeps the outbox
     * dispatcher off: an enabled dispatcher would publish the `standing-order.due.v1` event this
     * test asserts on, `StandingOrderDueConsumer` would self-consume it in the same JVM, fail the
     * sepa-payment call and `recordFailure()` the very order under assertion.
     */
    class FastSweepProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.scheduler.execution-cron" to "*/2 * * * * ?",
            "openbank.scheduler.execution-enabled" to "true",
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var repository: StandingOrderRepositoryImpl

    @Inject
    lateinit var outboxRepository: StandingOrderOutboxRepositoryImpl

    @Inject
    lateinit var clock: Clock

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun dueEventCount(orderId: UUID): Long = onEventLoop {
        Panache.withSession {
            outboxRepository.count("aggregateId = ?1 and eventType = ?2", orderId, DUE_EVENT_TYPE)
        }.awaitSuspending()
    }

    /** Polls until the sweep has written the order's outbox row, or the budget runs out. */
    private fun awaitDueEvent(orderId: UUID): Long {
        val deadline = System.nanoTime() + SWEEP_BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            val rows = dueEventCount(orderId)
            if (rows > 0L) return rows
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return dueEventCount(orderId)
    }

    private fun dueDailyOrder(id: UUID, due: LocalDate): StandingOrder {
        val now = Instant.now(clock)
        return StandingOrder(
            id = id,
            idempotencyKey = "sweep-it-$id",
            partyId = Ids.newId(),
            debitAccountId = Ids.newId(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Debtor",
            creditorIban = "DE75512108001245126199",
            creditorName = "Bob Creditor",
            creditorBic = null,
            amountMinorUnits = 4_200,
            currency = "EUR",
            // A ONCE order intentionally stays due until its payment is confirmed. With the
            // dispatcher disabled and this profile's two-second cron, that fixture can be picked
            // up again between the outbox assertion and the reload below. DAILY advances beyond
            // today's sweep while exercising the same scheduler, transaction and outbox path.
            frequency = Frequency.DAILY,
            paymentType = PaymentType.SEPA_CREDIT,
            remittanceInfo = null,
            startDate = due,
            endDate = null,
            nextExecutionDate = due,
            lastExecutionDate = null,
            executionCount = 0,
            failureCount = 0,
            status = StandingOrderStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `the scheduled sweep executes a due order and writes its outbox event`() {
        val id = Ids.newId()
        val today = LocalDate.now(clock)
        onEventLoop { repository.save(dueDailyOrder(id, today)) }

        assertThat(awaitDueEvent(id))
            .describedAs(
                "a scheduler-dispatched sweep must write exactly one $DUE_EVENT_TYPE outbox row for " +
                    "the due order — 0 means the sweep threw HR000068 off the Vert.x context (#2148)",
            )
            .isEqualTo(1L)

        val reloaded = onEventLoop { repository.findById(id) }
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.executionCount)
            .describedAs("the due order must be executed exactly once by the sweep")
            .isEqualTo(1)
        assertThat(reloaded.status)
            .describedAs(
                "the recurring order remains active after its due occurrence is scheduled",
            )
            .isEqualTo(StandingOrderStatus.ACTIVE)
        assertThat(reloaded.lastExecutionDate)
            .describedAs("the execution is stamped with the date it was due")
            .isEqualTo(today)
        assertThat(reloaded.nextExecutionDate)
            .describedAs("the fixture must advance beyond this sweep so the fast cron cannot reschedule it")
            .isEqualTo(today.plusDays(1))
    }

    private companion object {
        const val DUE_EVENT_TYPE = "standing-order.due.v1"

        /** Generous vs the 2s cron so a slow CI runner cannot flake the wait. */
        const val SWEEP_BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
