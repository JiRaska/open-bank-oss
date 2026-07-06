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
        // Both prior steps get compensated (most-recent-first), booking itself is not reversed.
        assertThat(calls.reverseCredit.get()).isEqualTo(1)
        assertThat(calls.reverseDebit.get()).isEqualTo(1)
        assertThat(calls.reverseBookToLedger.get()).isZero()
        assertThat(calls.rejectSettlement.get()).isEqualTo(1)
        assertThat(calls.order).containsExactly(
            "debitPayer",
            "creditPayee",
            "bookToLedger",
            "reverseCredit",
            "reverseDebit",
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

        // reverseCredit throws, but the workflow still runs reverseDebit and rejects.
        assertThat(result).isEqualTo(SettlementStatus.REJECTED)
        assertThat(calls.reverseCredit.get()).isEqualTo(1)
        assertThat(calls.reverseDebit.get()).isEqualTo(1)
        assertThat(calls.rejectSettlement.get()).isEqualTo(1)
    }

    /** In-process activities stub that records call order/counts and can fail selected steps. */
    private class RecordingActivities(
        private val failDebitPayer: Boolean = false,
        private val failCreditPayee: Boolean = false,
        private val failBookToLedger: Boolean = false,
        private val failReverseCredit: Boolean = false,
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
        }

        override fun rejectSettlement(settlementId: UUID) {
            order += "rejectSettlement"
            rejectSettlement.incrementAndGet()
        }
    }
}
