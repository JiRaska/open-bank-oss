// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.client.WorkflowOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for the Temporal settlement saga (ADR-0101 P3): debitPayer -> creditPayee ->
 * bookToLedger, with reverse-order compensation when any activity fails. Runs the real
 * [SettlementWorkflowImpl] against Temporal's in-memory [TestWorkflowEnvironment] with a
 * recording [SettlementActivities] stub, mirroring PaymentWorkflowImplTest (transaction-service)
 * and SepaPaymentWorkflowImplTest (sepa-payment).
 */
class SettlementWorkflowImplTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker

    private companion object {
        const val TASK_QUEUE = "test-settlement"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(SettlementWorkflowImpl::class.java)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun newWorkflow(): SettlementWorkflow = env.workflowClient.newWorkflowStub(
        SettlementWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("settlement-${UUID.randomUUID()}")
            .build(),
    )

    @Test
    fun `happy path debits, credits and books without any compensation`() {
        val calls = RecordingActivities()
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.BOOKED)
        assertThat(calls.debitPayer.get()).isEqualTo(1)
        assertThat(calls.creditPayee.get()).isEqualTo(1)
        assertThat(calls.bookToLedger.get()).isEqualTo(1)
        assertThat(calls.reverseDebit.get()).isZero()
        assertThat(calls.reverseCredit.get()).isZero()
        assertThat(calls.reverseBookToLedger.get()).isZero()
        assertThat(calls.rejectSettlement.get()).isZero()
    }

    @Test
    fun `bookToLedger failure reverses credit and debit, in that order, then rejects`() {
        val calls = RecordingActivities(failBookToLedger = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.REJECTED)
        // Debit and credit both ran before the ledger booking failed.
        assertThat(calls.debitPayer.get()).isEqualTo(1)
        assertThat(calls.creditPayee.get()).isEqualTo(1)
        // Both prior steps get compensated (most-recent-first), and the ledger compensation runs
        // too — #6410: it is registered BEFORE bookToLedger, because a bookToLedger that throws
        // may still have posted the journal. Here the stub's compensation finds no journal, so it
        // returns normally and the settlement is rejected cleanly.
        assertThat(calls.reverseCredit.get()).isEqualTo(1)
        assertThat(calls.reverseDebit.get()).isEqualTo(1)
        assertThat(calls.reverseBookToLedger.get()).isEqualTo(1)
        assertThat(calls.rejectSettlement.get()).isEqualTo(1)
        // The two balance reversals go first: they return customer funds, which outranks a GL
        // correcting entry. The three compensations are independent, so this is urgency order.
        assertThat(calls.order).containsExactly(
            "debitPayer",
            "creditPayee",
            "bookToLedger",
            "reverseCredit",
            "reverseDebit",
            "reverseBookToLedger",
            "rejectSettlement",
        )
    }

    @Test
    fun `creditPayee failure reverses only the debit then rejects`() {
        val calls = RecordingActivities(failCreditPayee = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.REJECTED)
        assertThat(calls.debitPayer.get()).isEqualTo(1)
        assertThat(calls.creditPayee.get()).isEqualTo(1)
        assertThat(calls.reverseDebit.get()).isEqualTo(1)
        assertThat(calls.reverseCredit.get()).isZero()
        assertThat(calls.bookToLedger.get()).isZero()
        assertThat(calls.rejectSettlement.get()).isEqualTo(1)
    }

    @Test
    fun `debitPayer failure compensates nothing and rejects directly`() {
        val calls = RecordingActivities(failDebitPayer = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.REJECTED)
        assertThat(calls.debitPayer.get()).isEqualTo(1)
        assertThat(calls.creditPayee.get()).isZero()
        assertThat(calls.reverseDebit.get()).isZero()
        assertThat(calls.reverseCredit.get()).isZero()
        assertThat(calls.reverseBookToLedger.get()).isZero()
        assertThat(calls.rejectSettlement.get()).isEqualTo(1)
    }

    @Test
    fun `a compensation activity that itself fails does not stop the remaining compensations`() {
        val calls = RecordingActivities(failBookToLedger = true, failReverseCredit = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        // reverseCredit throws, but the workflow still runs reverseDebit (and the ledger one).
        assertThat(calls.reverseCredit.get()).isEqualTo(1)
        assertThat(calls.reverseDebit.get()).isEqualTo(1)
        assertThat(calls.reverseBookToLedger.get()).isEqualTo(1)
        // It must NOT reject: see the REVERSAL_FAILED test below for why.
        assertThat(result).isEqualTo(SettlementStatus.REVERSAL_FAILED)
        assertThat(calls.rejectSettlement.get()).isZero()
    }

    /**
     * The money-path assertion of issue #6286.
     *
     * `reverseCredit` refused means the payee's credit could not be taken back — customer funds
     * have moved and have not returned. `SettlementActivitiesImpl.compensate` records
     * REVERSAL_FAILED for exactly that. Until #6286 the workflow then called `rejectSettlement`
     * unconditionally, overwriting it with REJECTED: the settlements table said the settlement was
     * cleanly rejected while the money was still out, and the age gauge behind
     * `SettlementReversalFailed` never saw a row to alert on, because the row left the state
     * within seconds.
     *
     * Asserting `rejectSettlement` was NOT called is the whole point — the previous version of
     * this test asserted the opposite and passed.
     */
    @Test
    fun `a refused money reversal leaves the settlement non-terminal and never rejects`() {
        val calls = RecordingActivities(failBookToLedger = true, failReverseCredit = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result)
            .describedAs("REJECTED here would erase the record that funds are still outstanding")
            .isEqualTo(SettlementStatus.REVERSAL_FAILED)
        assertThat(calls.rejectSettlement.get()).isZero()
        assertThat(calls.order).doesNotContain("rejectSettlement")
    }

    /**
     * The money-path assertion of issue #6410, and the direct inverse of the test it replaces.
     *
     * `reverseBookToLedger` used to be structurally unreachable: its compensation was registered
     * only after `bookToLedger` RETURNED, and `bookToLedger` is the last forward step, so nothing
     * could fail afterwards to trigger an unwind that included it. The previous version of this
     * test asserted that zero and passed — correctly, about dead code.
     *
     * What made it dead also made it wrong. `bookToLedger` posts the journal and *then* writes
     * BOOKED, so a throw from it does not mean the general ledger is clean: the posting can have
     * landed and the status write failed. Under the registered-after shape that settlement
     * unwound both balance movements, wrote REJECTED, and left a GL entry standing that nothing
     * in the row, the alerts or the audit trail ever mentioned.
     *
     * Registering the compensation first is what closes that, and it is safe only because
     * `reverseBookToLedger` establishes what the ledger actually holds instead of assuming.
     */
    @Test
    fun `the ledger compensation runs when bookToLedger fails, because the posting may have landed`() {
        val calls = RecordingActivities(failBookToLedger = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        newWorkflow().settle(UUID.randomUUID())

        assertThat(calls.reverseBookToLedger.get())
            .describedAs("a bookToLedger that threw may still have posted the journal")
            .isEqualTo(1)
    }

    /**
     * A ledger compensation that CONFIRMED a standing GL posting leaves the settlement
     * non-terminal in LEDGER_REVERSAL_UNSUPPORTED — the GL owes a correcting entry, and writing
     * REJECTED over it would erase the only durable record of that (the #6286 rule, applied to
     * the ledger half).
     */
    @Test
    fun `a confirmed standing GL posting is reported as LEDGER_REVERSAL_UNSUPPORTED and never rejects`() {
        val calls = RecordingActivities(failBookToLedger = true, failReverseBookToLedger = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED)
        assertThat(calls.rejectSettlement.get()).isZero()
    }

    /**
     * The reason `Compensation.onFailure` is a function of the failure and not a constant.
     *
     * `reverseBookToLedger` has two distinct failures. It writes LEDGER_REVERSAL_UNSUPPORTED when
     * a journal is confirmed to exist, and LEDGER_STATE_UNKNOWN when the lookup itself failed and
     * nobody knows. With a constant pairing the workflow would return the first for both, so a
     * settlement whose row said "nobody has checked the ledger" would be reported as "the ledger
     * definitely carries a posting" — sending an accountant to correct an entry that may not
     * exist, and losing the fact that the question is still open.
     */
    @Test
    fun `an unestablished ledger state is reported as LEDGER_STATE_UNKNOWN, not as a confirmed posting`() {
        val calls = RecordingActivities(
            failBookToLedger = true,
            failReverseBookToLedger = true,
            reverseBookToLedgerFailureType = "LedgerStateUnknown",
        )
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result)
            .describedAs("the workflow must report the status the activity actually wrote")
            .isEqualTo(SettlementStatus.LEDGER_STATE_UNKNOWN)
        assertThat(calls.rejectSettlement.get()).isZero()
    }

    /**
     * A refused money reversal outranks any ledger obligation: customer funds have moved and not
     * come back, which is critical, while a GL entry needing a correction is not.
     */
    @Test
    fun `a refused money reversal outranks a ledger obligation in the reported status`() {
        val calls = RecordingActivities(
            failBookToLedger = true,
            failReverseCredit = true,
            failReverseBookToLedger = true,
        )
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.REVERSAL_FAILED)
    }

    /** A clean unwind still reaches its terminal state — REJECTED must stay reachable. */
    @Test
    fun `a fully successful unwind still rejects`() {
        val calls = RecordingActivities(failBookToLedger = true)
        worker.registerActivitiesImplementations(calls)
        env.start()

        val result = newWorkflow().settle(UUID.randomUUID())

        assertThat(result).isEqualTo(SettlementStatus.REJECTED)
        assertThat(calls.rejectSettlement.get()).isEqualTo(1)
    }

    /** In-process activities stub that records call order/counts and can fail selected steps. */
    private class RecordingActivities(
        private val failDebitPayer: Boolean = false,
        private val failCreditPayee: Boolean = false,
        private val failBookToLedger: Boolean = false,
        private val failReverseCredit: Boolean = false,
        private val failReverseBookToLedger: Boolean = false,
        private val reverseBookToLedgerFailureType: String = "LedgerReversalUnsupported",
    ) : SettlementActivities {
        val debitPayer = AtomicInteger(0)
        val creditPayee = AtomicInteger(0)
        val bookToLedger = AtomicInteger(0)
        val reverseDebit = AtomicInteger(0)
        val reverseCredit = AtomicInteger(0)
        val reverseBookToLedger = AtomicInteger(0)
        val rejectSettlement = AtomicInteger(0)
        val order = mutableListOf<String>()

        override fun debitPayer(settlementId: UUID) {
            order += "debitPayer"
            debitPayer.incrementAndGet()
            if (failDebitPayer) throw ApplicationFailure.newNonRetryableFailure("balance down", "BalanceError")
        }

        override fun creditPayee(settlementId: UUID) {
            order += "creditPayee"
            creditPayee.incrementAndGet()
            if (failCreditPayee) throw ApplicationFailure.newNonRetryableFailure("balance down", "BalanceError")
        }

        override fun bookToLedger(settlementId: UUID) {
            order += "bookToLedger"
            bookToLedger.incrementAndGet()
            if (failBookToLedger) throw ApplicationFailure.newNonRetryableFailure("ledger down", "LedgerError")
        }

        override fun reverseDebit(settlementId: UUID) {
            order += "reverseDebit"
            reverseDebit.incrementAndGet()
        }

        override fun reverseCredit(settlementId: UUID) {
            order += "reverseCredit"
            reverseCredit.incrementAndGet()
            if (failReverseCredit) throw ApplicationFailure.newNonRetryableFailure("balance down", "BalanceError")
        }

        override fun reverseBookToLedger(settlementId: UUID) {
            order += "reverseBookToLedger"
            reverseBookToLedger.incrementAndGet()
            if (failReverseBookToLedger) {
                // The TYPE is what the workflow reads to tell a confirmed standing posting from an
                // unestablished one, so it is a parameter here rather than a literal.
                throw ApplicationFailure.newNonRetryableFailure(
                    "ledger compensation failed",
                    reverseBookToLedgerFailureType,
                )
            }
        }

        override fun rejectSettlement(settlementId: UUID) {
            order += "rejectSettlement"
            rejectSettlement.incrementAndGet()
        }
    }
}
