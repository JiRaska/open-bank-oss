// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.standingorder.integration

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.standingorder.application.port.`in`.StandingOrderUseCase
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
 * Regression coverage for #12839 — a ONCE standing order never reached COMPLETED.
 *
 * `StandingOrderService.confirmExecution` early-returned whenever `failureCount == 0` ("nothing
 * to reset"), but the domain's `StandingOrder.confirmExecution` is also the ONLY place a ONCE
 * order's status ever becomes COMPLETED (`recordExecution` deliberately does not complete ONCE
 * orders before the payment is attempted, and `calculateNextDate` never advances their
 * `nextExecutionDate`). So a ONCE order that succeeded on its first execution stayed ACTIVE
 * forever: `findDueForExecution` (`nextExecutionDate <= asOf AND status = 'ACTIVE'`) re-picked
 * it on EVERY daily sweep — `executionCount` inflated on each one and a duplicate
 * `standing-order.due.v1` outbox row was written per sweep, for the rest of the order's life.
 *
 * This test drives the real use case against real Postgres in the production call order —
 * sweep, consumer-confirm, second sweep — and asserts the order is COMPLETED after the confirm
 * and that the second sweep schedules nothing and emits no second due event. With the old
 * early-return, the second `executeOrders` schedules the order again (`executionCount` becomes
 * 2 and a second outbox row appears) and the reload shows ACTIVE.
 *
 * The sweep is driven through `executeOrders` directly rather than the cron: the defect is in
 * the use-case logic, not in scheduler threading (that shape is covered by
 * `StandingOrderExecutionSweepIT`), and the cron would race the assertions.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(StandingOrderOnceCompletionIT.NoDispatchProfile::class)
class StandingOrderOnceCompletionIT {

    /** The dispatcher stays off so the due outbox rows this test counts are never published. */
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
        )
    }

    @Inject
    lateinit var useCase: StandingOrderUseCase

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

    private fun dueOnceOrder(id: UUID, due: LocalDate): StandingOrder {
        val now = Instant.now(clock)
        return StandingOrder(
            id = id,
            idempotencyKey = "once-it-$id",
            partyId = Ids.newId(),
            debitAccountId = Ids.newId(),
            debtorIban = "DE89370400440532013000",
            debtorName = "Alice Debtor",
            creditorIban = "DE75512108001245126199",
            creditorName = "Bob Creditor",
            creditorBic = null,
            amountMinorUnits = 4_200,
            currency = "EUR",
            frequency = Frequency.ONCE,
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
    fun `a confirmed ONCE order completes and is never re-swept`() {
        val id = Ids.newId()
        val today = LocalDate.now(clock)
        onEventLoop { repository.save(dueOnceOrder(id, today)) }

        // Sweep 1: the ONCE order is due and gets scheduled (one due outbox row, count 1). Its
        // nextExecutionDate does NOT advance — for ONCE, calculateNextDate returns the date
        // unchanged — so whether sweep 2 re-picks it is decided purely by the status.
        val firstSweep = onEventLoop { useCase.executeOrders(today) }
        assertThat(firstSweep).isEqualTo(1)
        assertThat(dueEventCount(id)).isEqualTo(1L)

        // The consumer confirms the (replayed-idempotent) payment — success on the first try,
        // failureCount 0: exactly the case the old fast-path skipped.
        val confirmed = onEventLoop { useCase.confirmExecution(id) }
        assertThat(confirmed.status)
            .describedAs("confirming a successful ONCE order's payment must complete it (#12839)")
            .isEqualTo(StandingOrderStatus.COMPLETED)

        // Sweep 2: a COMPLETED order must not be due, nothing scheduled, no new due event.
        val secondSweep = onEventLoop { useCase.executeOrders(today) }
        assertThat(secondSweep)
            .describedAs("a COMPLETED ONCE order must not be re-picked by the daily sweep")
            .isEqualTo(0)
        assertThat(dueEventCount(id))
            .describedAs("no duplicate standing-order.due.v1 row may be emitted for a completed ONCE order")
            .isEqualTo(1L)

        val reloaded = onEventLoop { repository.findById(id) }
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.executionCount)
            .describedAs("executionCount must stay 1 — the sweep must not re-record the completed order")
            .isEqualTo(1)
        assertThat(reloaded.status).isEqualTo(StandingOrderStatus.COMPLETED)
    }

    private companion object {
        const val DUE_EVENT_TYPE = "standing-order.due.v1"
    }
}
