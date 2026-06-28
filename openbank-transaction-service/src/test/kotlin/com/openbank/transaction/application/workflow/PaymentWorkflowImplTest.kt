// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.transaction.domain.saga.SagaState
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
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

class PaymentWorkflowImplTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker

    private companion object {
        const val TASK_QUEUE = "test-payment-execution"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun newWorkflow(): PaymentWorkflow = env.workflowClient.newWorkflowStub(
        PaymentWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("payment-${UUID.randomUUID()}")
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build(),
    )

    @Test
    fun `happy path returns COMPLETED and never releases hold or reverses journal`() {
        val holdId = UUID.randomUUID()
        val journalId = UUID.randomUUID()
        val releaseCalls = AtomicInteger(0)
        val reverseCalls = AtomicInteger(0)
        worker.registerActivitiesImplementations(
            RecordingActivities(
                placeHoldResult = holdId,
                postJournalResult = journalId,
                releaseCalls = releaseCalls,
                reverseCalls = reverseCalls,
            ),
        )
        env.start()

        val result = newWorkflow().execute(UUID.randomUUID())

        assertThat(result).isEqualTo(SagaState.COMPLETED)
        assertThat(releaseCalls.get()).isZero()
        assertThat(reverseCalls.get()).isZero()
    }

    @Test
    fun `journal failure with placed hold reverses nothing and releases the hold`() {
        val holdId = UUID.randomUUID()
        val releaseCalls = AtomicInteger(0)
        val reverseCalls = AtomicInteger(0)
        worker.registerActivitiesImplementations(
            RecordingActivities(
                placeHoldResult = holdId,
                postJournalResult = null, // throws
                releaseCalls = releaseCalls,
                reverseCalls = reverseCalls,
            ),
        )
        env.start()

        val result = newWorkflow().execute(UUID.randomUUID())

        assertThat(result).isEqualTo(SagaState.COMPENSATED)
        // Journal never posted -> no reversal; hold was placed -> released.
        assertThat(reverseCalls.get()).isZero()
        assertThat(releaseCalls.get()).isEqualTo(1)
    }

    @Test
    fun `journal failure with sentinel hold (incoming credit) releases nothing`() {
        val releaseCalls = AtomicInteger(0)
        val reverseCalls = AtomicInteger(0)
        worker.registerActivitiesImplementations(
            RecordingActivities(
                placeHoldResult = PaymentActivities.SENTINEL_HOLD,
                postJournalResult = null, // throws
                releaseCalls = releaseCalls,
                reverseCalls = reverseCalls,
            ),
        )
        env.start()

        val result = newWorkflow().execute(UUID.randomUUID())

        assertThat(result).isEqualTo(SagaState.COMPENSATED)
        assertThat(reverseCalls.get()).isZero()
        assertThat(releaseCalls.get()).isZero()
    }

    @Test
    fun `placeHold failure compensates without releasing or reversing`() {
        val releaseCalls = AtomicInteger(0)
        val reverseCalls = AtomicInteger(0)
        worker.registerActivitiesImplementations(
            RecordingActivities(
                placeHoldResult = UUID.randomUUID(),
                postJournalResult = null,
                releaseCalls = releaseCalls,
                reverseCalls = reverseCalls,
                failPlaceHold = true,
            ),
        )
        env.start()

        val result = newWorkflow().execute(UUID.randomUUID())

        assertThat(result).isEqualTo(SagaState.COMPENSATED)
        // Hold never placed (holdId stays sentinel) and no journal posted -> nothing to unwind.
        assertThat(releaseCalls.get()).isZero()
        assertThat(reverseCalls.get()).isZero()
    }

    /** In-process activities stub that records compensation calls and can fail placeHold/postJournal. */
    private class RecordingActivities(
        private val placeHoldResult: UUID,
        private val postJournalResult: UUID?,
        private val releaseCalls: AtomicInteger,
        private val reverseCalls: AtomicInteger,
        private val failPlaceHold: Boolean = false,
    ) : PaymentActivities {
        override fun placeHold(transactionId: UUID): UUID {
            if (failPlaceHold) throw ApplicationFailure.newNonRetryableFailure("balance down", "BalanceError")
            return placeHoldResult
        }

        override fun postJournal(transactionId: UUID): UUID =
            postJournalResult ?: throw ApplicationFailure.newNonRetryableFailure("ledger down", "LedgerError")

        override fun releaseHold(holdId: UUID) {
            releaseCalls.incrementAndGet()
        }

        override fun reverseJournal(journalId: UUID) {
            reverseCalls.incrementAndGet()
        }
    }
}
