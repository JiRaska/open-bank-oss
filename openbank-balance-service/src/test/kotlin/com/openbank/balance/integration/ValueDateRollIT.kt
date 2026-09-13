// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.integration

import com.openbank.balance.application.port.`in`.GetBalanceQuery
import com.openbank.balance.application.port.`in`.PlaceHoldCommand
import com.openbank.balance.application.port.out.BalanceEventPublisher
import com.openbank.balance.application.usecase.BalanceService
import com.openbank.balance.application.usecase.InsufficientFundsException
import com.openbank.balance.domain.model.BalanceEvent
import com.openbank.balance.infrastructure.persistence.repository.BalancePanacheRepo
import com.openbank.balance.infrastructure.persistence.repository.BalanceRepositoryImpl
import com.openbank.balance.infrastructure.persistence.repository.LedgerProjectionEventPanacheRepo
import com.openbank.balance.infrastructure.persistence.repository.LedgerProjectionPortImpl
import com.openbank.balance.it.PostgresRedpandaTestResource
import com.openbank.libs.domain.calendar.AccountingClock
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADR-0178 Phase 2 (#1745) — a forward-value-dated credit must be excluded from the spendable
 * balance before its value date and included on it, against a real database and driven by the real
 * scheduler.
 *
 * ## What is actually wrong today
 *
 * `SettlementDateResolver` books a payment to the **next business day** whenever it arrives at or
 * after the 16:00 Prague cut-off, or at a weekend — so `Transaction.bookingDate` is routinely a
 * future date, and `PaymentActivitiesImpl` passes it to the ledger as `entryDate`. For an incoming
 * payment `PaymentJournalFactory` CREDITs the payee's deposit control, and the ledger's control
 * balance is value-dated (`entry_date <= :asOf`), so the GL does not recognise that credit until
 * the booking date. balance-service's projection, however, applies the delta on event receipt and
 * moves `availableAmount` in lock-step — so the payee could spend it days early. This is not the
 * exotic "welcome bonus" case: it is every after-hours and weekend incoming payment.
 *
 * ## Why the scheduler is driven and not called
 *
 * `%test.quarkus.scheduler.enabled` is `false` for this whole service, so no existing test can see
 * a scheduler defect at all — and a direct call to `rollDaily()` would supply the very Vert.x
 * context the scheduler does not, passing against a `runBlocking`-shaped body that throws
 * `HR000068` on every real tick and does nothing (#2148/#2187). The profile below switches the
 * scheduler back on and shrinks the cron to every two seconds.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@TestProfile(ValueDateRollIT.FastRollProfile::class)
class ValueDateRollIT {

    /**
     * Literal values only — a `QuarkusTestProfile` loads in a different classloader from the test
     * class, so a companion object initializes twice and a randomized id would hand the scheduler
     * one value and the assertion another.
     */
    class FastRollProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.balance.value-date-roll.cron" to "*/2 * * * * ?",
            // The reconciliation tick calls the ledger REST client, which is not available here.
            // Push it far out rather than leaving it on the default 23:30 — this profile turns the
            // whole scheduler on, so it would otherwise be armed.
            "openbank.reconciliation.cron" to "0 0 5 29 2 ?",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(RecordingEventPublisher::class.java)
    }

    /** Captures what the roll announced, in place of the Kafka publisher. */
    @Alternative
    @ApplicationScoped
    class RecordingEventPublisher : BalanceEventPublisher {
        override suspend fun publish(event: BalanceEvent) {
            published.add(event)
        }

        companion object {
            val published = CopyOnWriteArrayList<BalanceEvent>()
        }
    }

    @Inject
    lateinit var balanceService: BalanceService

    @Inject
    lateinit var balanceRepo: BalanceRepositoryImpl

    @Inject
    lateinit var projection: LedgerProjectionPortImpl

    @Inject
    lateinit var balancePanacheRepo: BalancePanacheRepo

    @Inject
    lateinit var projectionRepo: LedgerProjectionEventPanacheRepo

    @Inject
    lateinit var accountingClock: AccountingClock

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun await(ready: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (System.nanoTime() < deadline) {
            if (ready()) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return ready()
    }

    @BeforeEach
    fun clean() {
        RecordingEventPublisher.published.clear()
        onEventLoop {
            Panache.withTransaction {
                projectionRepo.deleteAll().flatMap { balancePanacheRepo.deleteAll() }
            }.awaitSuspending()
        }
    }

    /**
     * Seeds through the real projection path so `balances` and `ledger_projection_event` stay
     * consistent — exactly what the live consumer does with an `AccountBookedChangedEvent`.
     */
    private suspend fun project(accountId: UUID, amount: String, entryDate: java.time.LocalDate) {
        projection.applyBookedDelta(
            journalEntryId = UUID.randomUUID(),
            accountId = accountId,
            currency = "CZK",
            delta = BigDecimal(amount),
            transactionId = UUID.randomUUID(),
            entryDate = entryDate,
            actorId = com.openbank.balance.domain.model.BalanceEventActors.LEDGER_PROJECTION,
        )
    }

    @Test
    fun `a credit booked to a future date is excluded from the spendable balance, and included on its value date`() {
        val today = accountingClock.today()
        val futureDated = UUID.randomUUID()
        val maturedToday = UUID.randomUUID()

        onEventLoop {
            // Both accounts: 3 000.00 effective days ago, plus a 2 000.00 incoming payment.
            project(futureDated, "3000.00", today.minusDays(5))
            // Friday evening arrival, booked to Monday — not yet effective.
            project(futureDated, "2000.00", today.plusDays(3))

            project(maturedToday, "3000.00", today.minusDays(5))
            // The same payment one value date later: today IS its booking date.
            project(maturedToday, "2000.00", today)
        }

        val beforeValueDate = onEventLoop { balanceService.getBalance(GetBalanceQuery(futureDated, "CZK")) }
        val onValueDate = onEventLoop { balanceService.getBalance(GetBalanceQuery(maturedToday, "CZK")) }

        // Both carry the same receipt-dated running total: the projection booked each on receipt.
        assertThat(beforeValueDate.availableAmount).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(onValueDate.availableAmount).isEqualByComparingTo(BigDecimal("5000.00"))

        // ...and they differ on the value-date basis, which is the whole point.
        assertThat(beforeValueDate.notYetEffectiveCredit).isEqualByComparingTo(BigDecimal("2000.00"))
        assertThat(beforeValueDate.effectiveAvailable())
            .describedAs("a credit booked to a future business day is not yet spendable")
            .isEqualByComparingTo(BigDecimal("3000.00"))
        assertThat(beforeValueDate.effectiveBooked()).isEqualByComparingTo(BigDecimal("3000.00"))

        assertThat(onValueDate.notYetEffectiveCredit).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(onValueDate.effectiveAvailable())
            .describedAs("on its value date the same credit is spendable in full")
            .isEqualByComparingTo(BigDecimal("5000.00"))
    }

    @Test
    fun `the cover decision refuses to reserve a not-yet-effective credit, and allows the effective part`() {
        val today = accountingClock.today()
        val accountId = UUID.randomUUID()

        onEventLoop {
            project(accountId, "3000.00", today.minusDays(5))
            project(accountId, "2000.00", today.plusDays(3))
        }

        // 3 000.01 is covered by the receipt-dated available (5 000.00) and NOT by the effective
        // one (3 000.00). Before this change the hold was granted and the customer spent money the
        // general ledger will not recognise for three days.
        assertThatThrownBy {
            onEventLoop {
                balanceService.placeHold(
                    PlaceHoldCommand(accountId, BigDecimal("3000.01"), "CZK", "payment", UUID.randomUUID().toString()),
                )
            }
        }.isInstanceOf(InsufficientFundsException::class.java)
            .hasMessageContaining("not yet effective")

        // The effective part is still fully spendable — this restricts the future credit only.
        val hold = onEventLoop {
            balanceService.placeHold(
                PlaceHoldCommand(accountId, BigDecimal("3000.00"), "CZK", "payment", UUID.randomUUID().toString()),
            )
        }
        assertThat(hold.amount).isEqualByComparingTo(BigDecimal("3000.00"))

        val after = onEventLoop { balanceService.getBalance(GetBalanceQuery(accountId, "CZK")) }
        assertThat(after.reservedAmount).isEqualByComparingTo(BigDecimal("3000.00"))
        assertThat(after.effectiveAvailable()).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `the real scheduler announces a credit that matured today`() {
        val today = accountingClock.today()
        val accountId = UUID.randomUUID()

        onEventLoop {
            project(accountId, "3000.00", today.minusDays(5))
            project(accountId, "2000.00", today)
        }

        val announced = await {
            RecordingEventPublisher.published.any { it.accountId == accountId }
        }

        assertThat(announced)
            .describedAs(
                "a scheduler-dispatched value-date roll must announce the maturity — never " +
                    "announcing one means the tick threw HR000068 off the Vert.x context before " +
                    "the cluster lock's first query (#2148/#2187)",
            )
            .isTrue()

        val event = RecordingEventPublisher.published.first { it.accountId == accountId }
        // The announced figures are the POST-maturity ones: the credit that matured today is no
        // longer in the tail (`entry_date > today`), so the full 5 000.00 is effective.
        assertThat(event.availableAmount).isEqualByComparingTo(BigDecimal("5000.00"))
        assertThat(event.bookedAmount).isEqualByComparingTo(BigDecimal("5000.00"))
        // Maturity is not a movement: the money was booked on its posting day.
        assertThat(event.amount).isEqualByComparingTo(BigDecimal.ZERO)
    }

    private companion object {
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
