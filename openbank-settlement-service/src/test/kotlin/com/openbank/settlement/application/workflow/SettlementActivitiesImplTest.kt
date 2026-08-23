// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerJournalLookupPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.ReverseCreditPort
import com.openbank.settlement.application.port.out.ReverseDebitPort
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.temporal.failure.ApplicationFailure
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Also covers issue #1502: each state transition below must emit an [AuditEvent] onto the shared
 * libs audit pipeline so settlement-service has a DORA Art. 17 reconstructable audit trail.
 */
class SettlementActivitiesImplTest {

    private val settlementRepository: SettlementRepository = mockk(relaxed = true)
    private val debitPort: DebitPort = mockk(relaxed = true)
    private val creditPort: CreditPort = mockk(relaxed = true)
    private val ledgerPort: LedgerPort = mockk(relaxed = true)
    private val auditPublisher: AuditEventPublisher = mockk(relaxed = true)
    private val reverseDebitPort: ReverseDebitPort = mockk(relaxed = true)
    private val reverseCreditPort: ReverseCreditPort = mockk(relaxed = true)
    private val metrics: SettlementMetricsPort = mockk(relaxed = true)
    private val ledgerJournalLookupPort: LedgerJournalLookupPort = mockk(relaxed = true)

    private lateinit var activities: SettlementActivitiesImpl

    private fun settlement(id: UUID, status: SettlementStatus) = Settlement(
        id = id,
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("10.00"),
        currency = "CZK",
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        // TestableActivities overrides runOnVertxContext to run synchronously — the production impl
        // needs a real Vert.x context (VertxContextSupport), which a plain unit test does not have.
        activities = TestableActivities(
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
        // Default: the ledger HOLDS a journal for the settlement. The compensation's whole job is
        // to distinguish that from an empty ledger, so no test may rely on an unset default.
        coEvery { ledgerJournalLookupPort.countJournalsForSettlement(any()) } returns 1
        coEvery { settlementRepository.updateStatus(any(), any()) } answers {
            settlement(firstArg(), secondArg())
        }
    }

    @Test
    fun `debitPayer calls debit port and sets DEBITED status`() {
        val id = UUID.randomUUID()
        activities.debitPayer(id)
        coVerify { debitPort.debit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.DEBITED) }
    }

    @Test
    fun `debitPayer publishes a settlement_debit audit event`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit

        activities.debitPayer(id)

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("settlement.debit")
            assertThat(e.actorId).isEqualTo("settlement-service")
            assertThat(e.actorType).isEqualTo("SERVICE")
            assertThat(e.resourceType).isEqualTo("settlement")
            assertThat(e.resourceId).isEqualTo(id.toString())
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["status"]).isEqualTo("DEBITED")
        })
    }

    @Test
    fun `creditPayee calls credit port and sets CREDITED status`() {
        val id = UUID.randomUUID()
        activities.creditPayee(id)
        coVerify { creditPort.credit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.CREDITED) }
    }

    @Test
    fun `bookToLedger calls ledger port and sets BOOKED status`() {
        val id = UUID.randomUUID()
        activities.bookToLedger(id)
        coVerify { ledgerPort.book(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.BOOKED) }
    }

    // ---- Compensation (issue #6037) --------------------------------------------------------
    // These four tests replace three that asserted the DEFECT: they read
    // `reverseDebit does not call debit port and sets REVERSED status` and passed precisely
    // because no money moved. A test that pins a stub's behaviour makes the stub permanent.

    @Test
    fun `reverseDebit calls the reversal port and only then sets REVERSED`() {
        val id = UUID.randomUUID()

        activities.reverseDebit(id)

        coVerify(exactly = 1) { reverseDebitPort.reverseDebit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REVERSED) }
        // Ordering is the point: a status written before the money call is a claim not yet earned.
        coVerifyOrder {
            reverseDebitPort.reverseDebit(id)
            settlementRepository.updateStatus(id, SettlementStatus.REVERSED)
        }
    }

    @Test
    fun `reverseCredit calls the reversal port and only then sets CREDITED_REVERSED`() {
        val id = UUID.randomUUID()

        activities.reverseCredit(id)

        coVerify(exactly = 1) { reverseCreditPort.reverseCredit(id) }
        coVerifyOrder {
            reverseCreditPort.reverseCredit(id)
            settlementRepository.updateStatus(id, SettlementStatus.CREDITED_REVERSED)
        }
    }

    @Test
    fun `a refused reversal records REVERSAL_FAILED, audits FAILURE and rethrows`() {
        val id = UUID.randomUUID()
        // balance-service refuses the counter-debit: the payee has spent the funds (422).
        coEvery { reverseCreditPort.reverseCredit(id) } throws IllegalStateException("insufficient funds")
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit

        assertThatThrownBy { activities.reverseCredit(id) }
            .isInstanceOf(IllegalStateException::class.java)

        // The money did NOT come back, so the row must not say CREDITED_REVERSED.
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REVERSAL_FAILED) }
        coVerify(exactly = 0) { settlementRepository.updateStatus(id, SettlementStatus.CREDITED_REVERSED) }
        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("settlement.reverse-credit")
            assertThat(e.result).isEqualTo(AuditResult.FAILURE)
            assertThat(e.payload["status"]).isEqualTo("REVERSAL_FAILED")
        })
    }

    @Test
    fun `a CONFIRMED journal fails loudly as unsupported instead of claiming a reversal`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit

        assertThatThrownBy { activities.reverseBookToLedger(id) }
            .isInstanceOf(ApplicationFailure::class.java)
            .hasMessageContaining("Ledger reversal is not implemented")

        // It ASKED the ledger rather than assuming — that lookup is the difference between
        // summoning an accountant to a real GL entry and to one that was never posted.
        coVerify { ledgerJournalLookupPort.countJournalsForSettlement(id) }

        coVerify(exactly = 0) { ledgerPort.book(any()) }
        // Its own value, never the old LEDGER_REVERSED, which asserted an unwind that never ran.
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED) }
        @Suppress("DEPRECATION")
        coVerify(exactly = 0) { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_REVERSED) }
        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.result).isEqualTo(AuditResult.FAILURE)
        })
    }

    @Test
    fun `the unsupported ledger reversal is NON-retryable so it cannot delay the balance unwinds`() {
        // SettlementWorkflowImpl runs compensations LIFO and catches ActivityFailure per step, so a
        // retryable failure here would burn five attempts of backoff before reverseCredit and
        // reverseDebit — the two that actually return money — even got to run.
        assertThatThrownBy { activities.reverseBookToLedger(UUID.randomUUID()) }
            .isInstanceOfSatisfying(ApplicationFailure::class.java) { failure ->
                assertThat(failure.isNonRetryable).isTrue()
                assertThat(failure.type).isEqualTo("LedgerReversalUnsupported")
            }
    }

    /**
     * The no-op outcome of issue #6410, and the reason it is not a boolean.
     *
     * `bookToLedger` posts the journal and then writes BOOKED, so a bookToLedger that threw may
     * have posted nothing at all — the ordinary "ledger refused it" case. Reporting that as
     * LEDGER_REVERSAL_UNSUPPORTED sends an accountant after an entry that does not exist, and
     * noise on the one control that makes a real GL discrepancy visible is what hides a real GL
     * discrepancy. Reporting it as a plain success would claim a reversal that never happened —
     * the `PushResult.skipped()` shape. So it gets its own value and does NOT throw, which is
     * what lets the workflow go on to reject the settlement cleanly.
     */
    @Test
    fun `an empty ledger is LEDGER_NOT_POSTED, records SUCCESS, and does not fail the compensation`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit
        coEvery { ledgerJournalLookupPort.countJournalsForSettlement(id) } returns 0

        activities.reverseBookToLedger(id)

        coVerify { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_NOT_POSTED) }
        coVerify(exactly = 0) {
            settlementRepository.updateStatus(id, SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED)
        }
        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["status"]).isEqualTo("LEDGER_NOT_POSTED")
        })
    }

    /**
     * A lookup that could not answer must not be rounded to either neighbour: "we could not
     * check" is a third fact. It is RETRYABLE, unlike the unsupported case — an unreachable
     * ledger is the one failure here a retry can genuinely resolve.
     */
    @Test
    fun `a failed lookup is LEDGER_STATE_UNKNOWN and is retryable`() {
        val id = UUID.randomUUID()
        coEvery {
            ledgerJournalLookupPort.countJournalsForSettlement(id)
        } throws IllegalStateException("ledger unreachable")

        assertThatThrownBy { activities.reverseBookToLedger(id) }
            .isInstanceOfSatisfying(ApplicationFailure::class.java) { failure ->
                assertThat(failure.isNonRetryable)
                    .describedAs("an unreachable ledger is the one case a retry can resolve")
                    .isFalse()
                assertThat(failure.type).isEqualTo("LedgerStateUnknown")
            }

        coVerify { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_STATE_UNKNOWN) }
        coVerify(exactly = 0) {
            settlementRepository.updateStatus(id, SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED)
        }
        coVerify(exactly = 0) { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_NOT_POSTED) }
    }

    @Test
    fun `rejectSettlement sets REJECTED status without port calls`() {
        val id = UUID.randomUUID()
        activities.rejectSettlement(id)
        coVerify(exactly = 0) { debitPort.debit(any()) }
        coVerify(exactly = 0) { creditPort.credit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REJECTED) }
    }

    @Test
    fun `rejectSettlement publishes a FAILURE settlement_reject audit event`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit

        activities.rejectSettlement(id)

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("settlement.reject")
            assertThat(e.result).isEqualTo(AuditResult.FAILURE)
            assertThat(e.resourceId).isEqualTo(id.toString())
        })
    }
}

/** Runs the activity bodies synchronously, bypassing the real Vert.x-context bridge. */
@Suppress("LongParameterList")
private class TestableActivities(
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
