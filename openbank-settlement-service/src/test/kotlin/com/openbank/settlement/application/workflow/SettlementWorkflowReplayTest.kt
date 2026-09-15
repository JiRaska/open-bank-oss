// SPDX-License-Identifier: Apache-2.0
package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.mockk.every
import io.mockk.mockk
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SettlementWorkflowReplayTest {
    @Test
    fun `legacy successful and rejected histories replay without scheduling the new activity`() {
        for (failedStep in listOf("none", "debit", "credit", "ledger")) {
            TestWorkflowEnvironment.newInstance().use { env ->
                val worker = env.newWorker("legacy-settlement")
                worker.registerWorkflowImplementationTypes(LegacySettlementWorkflowImpl::class.java)
                val activities = mockk<SettlementActivities>(relaxed = true)
                val failure = ApplicationFailure.newNonRetryableFailure("unavailable", "LegacyFailure")
                when (failedStep) {
                    "debit" -> every { activities.debitPayer(any()) } throws failure
                    "credit" -> every { activities.creditPayee(any()) } throws failure
                    "ledger" -> every { activities.bookToLedger(any()) } throws failure
                }
                worker.registerActivitiesImplementations(activities)
                env.start()
                val workflow = env.workflowClient.newWorkflowStub(
                    SettlementWorkflow::class.java,
                    WorkflowOptions.newBuilder().setTaskQueue("legacy-settlement").build(),
                )
                val stub = WorkflowStub.fromTyped(workflow)
                val execution = stub.start(UUID.randomUUID())
                assertThat(stub.getResult(SettlementStatus::class.java)).isEqualTo(
                    if (failedStep == "none") SettlementStatus.BOOKED else SettlementStatus.REJECTED,
                )
                WorkflowReplayer.replayWorkflowExecution(
                    env.getWorkflowExecutionHistory(execution),
                    SettlementWorkflowImpl::class.java,
                )
            }
        }
    }
}
