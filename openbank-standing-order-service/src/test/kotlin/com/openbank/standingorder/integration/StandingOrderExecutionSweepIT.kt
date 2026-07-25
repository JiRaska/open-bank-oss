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
import com.openbank.standingorder.infrastructure.scheduler.StandingOrderExecutionScheduler
import com.openbank.standingorder.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Real-DB coverage for the daily execution sweep (issue #2148). The scheduler's `sweep()` used to be
 * `fun sweep(): Unit = runBlocking { … }`, which runs the coroutine on the bare scheduler
 * executor-thread — no Vert.x context — so the first Hibernate-Reactive call (`findDueForExecution`)
 * threw `HR000068` and the whole sweep aborted with zero outbox rows. `sweep()` is now a `suspend fun`
 * (Quarkus dispatches it on a Vert.x context, like the sibling `StandingOrderOutboxDispatcher`).
 *
 * The old `StandingOrderServiceTest` mocks the repository, so `executeOrders` never touched a real
 * reactive session and the threading never ran — the same masking class as the pause/cancel 500
 * (#2077) and the consent outbox 500 (#1521). This IT drives the sweep through a real Vert.x context
 * and a real DB and asserts a due order produces a `standing_order_outbox` row — the coverage the
 * mocked unit test lacked.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
class StandingOrderExecutionSweepIT {

    @Inject
    lateinit var scheduler: StandingOrderExecutionScheduler

    @Inject
    lateinit var orderRepository: StandingOrderRepositoryImpl

    @Inject
    lateinit var outboxRepository: StandingOrderOutboxRepositoryImpl

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun dueOrder(id: UUID, dueOn: LocalDate): StandingOrder {
        val now = Instant.now()
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
            amountMinorUnits = 1_000,
            currency = "EUR",
            frequency = Frequency.MONTHLY,
            paymentType = PaymentType.SEPA_CREDIT,
            remittanceInfo = null,
            startDate = dueOn,
            endDate = null,
            nextExecutionDate = dueOn,
            lastExecutionDate = null,
            executionCount = 0,
            failureCount = 0,
            status = StandingOrderStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `sweep executes a due order and enqueues an outbox row (issue 2148)`() {
        val id = Ids.newId()
        // Clock is Clock.systemUTC() (ClockProducer), so the sweep's asOf is today; make the order due.
        onEventLoop { orderRepository.save(dueOrder(id, LocalDate.now(ZoneOffset.UTC))) }

        val before = onEventLoop { outboxRepository.countProcessable() }

        // Pre-#2148 this threw HR000068 on the executor-thread and enqueued nothing; with the suspend
        // fix it runs the reactive sweep to completion on a Vert.x context.
        onEventLoop { scheduler.sweep() }

        val after = onEventLoop { outboxRepository.countProcessable() }
        assertThat(after)
            .describedAs("the daily sweep must enqueue a standing-order.due outbox row for the due order")
            .isGreaterThan(before)
    }
}
