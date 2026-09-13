// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerJournalLookupPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.ReverseCreditPort
import com.openbank.settlement.application.port.out.ReverseDebitPort
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.infrastructure.observability.SettlementMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.temporal.failure.ApplicationFailure
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Proves the settlement meters record **on the saga path that claims to move them**, using a real
 * [SettlementMetricsAdapter] over a real registry rather than a verified mock.
 *
 * The distinction matters here more than usual. A mock verification shows the activity *called* the
 * port; it cannot show that the counter the alert reads actually moved, nor that the forward legs
 * are separated from the compensations, nor — the case this service was silent about — that a
 * settlement which was compensated all the way back to REJECTED is counted as `rejected` and not as
 * a success. Every test below therefore carries its negative control: the counter that must NOT
 * have moved is asserted alongside the one that must.
 */
class SettlementActivitiesMetricsTest {

    private val settlementRepository: SettlementRepository = mockk(relaxed = true)
    private val debitPort: DebitPort = mockk(relaxed = true)
    private val creditPort: CreditPort = mockk(relaxed = true)
    private val ledgerPort: LedgerPort = mockk(relaxed = true)
    private val auditPublisher: AuditEventPublisher = mockk(relaxed = true)
    private val reverseDebitPort: ReverseDebitPort = mockk(relaxed = true)
    private val reverseCreditPort: ReverseCreditPort = mockk(relaxed = true)

    private val registry = SimpleMeterRegistry()
    private val metrics = SettlementMetricsAdapter().apply { bindTo(registry) }
    private val ledgerJournalLookupPort: LedgerJournalLookupPort = mockk(relaxed = true)

    private lateinit var activities: SettlementActivitiesImpl

    private fun terminal(outcome: String): Double =
        registry.find(SettlementMetricsAdapter.TERMINAL_METRIC).tag("outcome", outcome).counter()?.count() ?: 0.0

    private fun sagaStep(step: String, outcome: String): Double =
        registry.find(SettlementMetricsAdapter.SAGA_STEPS_METRIC)
            .tag("step", step)
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    @BeforeEach
    fun setUp() {
        activities = MetricsTestableActivities(
            settlementRepository,
            debitPort,
            creditPort,
            ledgerPort,
            auditPublisher,
            reverseDebitPort,
            reverseCreditPort,
            metrics,
            ledgerJournalLookupPort,
        )
        // The row carries a 30s-old createdAt so the cycle timer has something non-zero to record —
        // a duration of exactly zero would pass even if the timer never saw the real timestamps.
        coEvery { settlementRepository.updateStatus(any(), any()) } answers {
            val now = Instant.now()
            Settlement(
                id = firstArg(),
                payerAccountId = UUID.randomUUID(),
                payeeAccountId = UUID.randomUUID(),
                amount = BigDecimal("250.00"),
                currency = "CZK",
                status = secondArg(),
                createdAt = now.minusSeconds(THIRTY_SECONDS),
                updatedAt = now,
            )
        }
    }

    @Test
    fun `bookToLedger records a booked settlement, its cycle duration and its amount`() {
        activities.bookToLedger(UUID.randomUUID())

        assertThat(terminal("booked")).isEqualTo(1.0)
        // The saga's only success terminus — a booking must never also read as a rejection.
        assertThat(terminal("rejected")).isEqualTo(0.0)
        assertThat(sagaStep("ledger_book", "completed")).isEqualTo(1.0)
        assertThat(sagaStep("ledger_book", "failed")).isEqualTo(0.0)

        val timer = registry.find(SettlementMetricsAdapter.CYCLE_DURATION_METRIC).tag("outcome", "booked").timer()
        assertThat(timer!!.count()).isEqualTo(1L)
        // Origination -> booking, read off the row: proves the timer saw the real timestamps and is
        // not recording a constant zero.
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isCloseTo(THIRTY_SECONDS_D, offset(ONE_SECOND))

        val amounts = registry.find(SettlementMetricsAdapter.BOOKED_AMOUNT_METRIC).tag("currency", "CZK").summary()
        assertThat(amounts!!.totalAmount()).isEqualTo(250.0)
    }

    @Test
    fun `the forward legs before booking record their step but no terminal outcome`() {
        val id = UUID.randomUUID()

        activities.debitPayer(id)
        activities.creditPayee(id)

        assertThat(sagaStep("debit", "completed")).isEqualTo(1.0)
        assertThat(sagaStep("credit", "completed")).isEqualTo(1.0)
        // Money has moved but the settlement is not done. Counting these as terminal is exactly the
        // "success that never left the process" shape this repo has shipped before.
        assertThat(terminal("booked")).isEqualTo(0.0)
        assertThat(terminal("rejected")).isEqualTo(0.0)
    }

    @Test
    fun `a compensated saga ending in rejectSettlement is counted as rejected, never as booked`() {
        val id = UUID.randomUUID()

        activities.reverseDebit(id)
        activities.reverseCredit(id)
        // #6410 gave reverseBookToLedger three outcomes instead of one, so this test has to say
        // WHICH it is exercising. A journal exists, which is the case that still cannot be reversed
        // and must therefore show as FAILED. The relaxed mock's default of 0 would take the
        // LEDGER_NOT_POSTED path, where a clean return is correct and the step completes.
        coEvery { ledgerJournalLookupPort.countJournalsForSettlement(id) } returns 1
        // Ledger reversal is NOT implemented and fails loudly on purpose (#6037): settlement-service
        // cannot reverse a journal, so the activity records LEDGER_REVERSAL_UNSUPPORTED and throws
        // rather than claiming an unwind that did not happen. The step meter must therefore show it
        // as FAILED — counting it as completed would restore exactly the false-success the status
        // change was made to remove. SettlementWorkflowImpl catches this per compensation, so the
        // saga still reaches rejectSettlement.
        assertThatThrownBy { activities.reverseBookToLedger(id) }
            .isInstanceOf(ApplicationFailure::class.java)
        activities.rejectSettlement(id)

        assertThat(terminal("rejected")).isEqualTo(1.0)
        assertThat(terminal("booked")).isEqualTo(0.0)
        assertThat(sagaStep("reverse_debit", "completed")).isEqualTo(1.0)
        assertThat(sagaStep("reverse_credit", "completed")).isEqualTo(1.0)
        assertThat(sagaStep("reverse_ledger_book", "failed")).isEqualTo(1.0)
        assertThat(sagaStep("reverse_ledger_book", "completed")).isEqualTo(0.0)
        assertThat(sagaStep("reject", "completed")).isEqualTo(1.0)
    }

    @Test
    fun `a failing activity is counted as failed, is not counted as completed, and still throws`() {
        // Temporal owns the retry/compensation decision — the meter must observe the attempt, never
        // swallow it, or instrumenting the saga would silently change its behaviour.
        coEvery { debitPort.debit(any()) } throws IllegalStateException("balance-service unavailable")

        assertThatThrownBy { activities.debitPayer(UUID.randomUUID()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("balance-service unavailable")

        assertThat(sagaStep("debit", "failed")).isEqualTo(1.0)
        assertThat(sagaStep("debit", "completed")).isEqualTo(0.0)
        assertThat(terminal("booked")).isEqualTo(0.0)
    }

    @Test
    fun `a booking that fails at the ledger records no booked settlement`() {
        coEvery { ledgerPort.book(any()) } throws IllegalStateException("ledger rejected the journal")

        assertThatThrownBy { activities.bookToLedger(UUID.randomUUID()) }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(sagaStep("ledger_book", "failed")).isEqualTo(1.0)
        // The whole point of recording the counter after the ledger write: a failed booking must
        // not appear in the series the "nothing has settled" alert reads.
        assertThat(terminal("booked")).isEqualTo(0.0)
    }

    private companion object {
        const val THIRTY_SECONDS = 30L
        const val ONE_SECOND = 1.0
        const val THIRTY_SECONDS_D = 30.0
    }
}

/** Runs the activity bodies synchronously, bypassing the real Vert.x-context bridge. */
@Suppress("LongParameterList")
private class MetricsTestableActivities(
    settlementRepository: SettlementRepository,
    debitPort: DebitPort,
    creditPort: CreditPort,
    ledgerPort: LedgerPort,
    auditPublisher: AuditEventPublisher,
    reverseDebitPort: ReverseDebitPort,
    reverseCreditPort: ReverseCreditPort,
    metrics: SettlementMetricsPort,
    ledgerJournalLookupPort: LedgerJournalLookupPort,
) : SettlementActivitiesImpl(
    settlementRepository,
    debitPort,
    creditPort,
    ledgerPort,
    auditPublisher,
    reverseDebitPort,
    reverseCreditPort,
    metrics,
    ledgerJournalLookupPort,
) {
    override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
}
