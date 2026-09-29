// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.enums.v1.RetryState
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowStub
import io.temporal.failure.CanceledFailure
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TemporalCaseCancellationAdapterTest {
    private val workflowClient = mockk<WorkflowClient>()
    private val workflowStub = mockk<WorkflowStub>()
    private val adapter = TemporalCaseCancellationAdapter(workflowClient)

    @Test
    fun `records success only after Temporal confirms cancellation`() {
        every { workflowClient.newUntypedWorkflowStub(WORKFLOW_ID) } returns workflowStub
        every { workflowStub.cancel() } returns Unit
        every { workflowStub.getResult(Void::class.java) } throws workflowFailure(CanceledFailure("cancelled"))

        assertThatCode { adapter.cancelAndAwait(WORKFLOW_ID) }.doesNotThrowAnyException()

        verifyOrder {
            workflowStub.cancel()
            workflowStub.getResult(Void::class.java)
        }
    }

    @Test
    fun `rejects a workflow that completes before cancellation is confirmed`() {
        every { workflowClient.newUntypedWorkflowStub(WORKFLOW_ID) } returns workflowStub
        every { workflowStub.cancel() } returns Unit
        every { workflowStub.getResult(Void::class.java) } returns null

        assertThatThrownBy { adapter.cancelAndAwait(WORKFLOW_ID) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("workflow $WORKFLOW_ID completed instead of acknowledging cancellation")
    }

    @Test
    fun `propagates a terminal workflow failure that is not cancellation`() {
        val terminalFailure = workflowFailure(IllegalStateException("workflow failed"))
        every { workflowClient.newUntypedWorkflowStub(WORKFLOW_ID) } returns workflowStub
        every { workflowStub.cancel() } returns Unit
        every { workflowStub.getResult(Void::class.java) } throws terminalFailure

        assertThatThrownBy { adapter.cancelAndAwait(WORKFLOW_ID) }.isSameAs(terminalFailure)
    }

    private fun workflowFailure(cause: Throwable) = WorkflowFailedException(
        WorkflowExecution.newBuilder().setWorkflowId(WORKFLOW_ID).setRunId("run-1").build(),
        "CaseWorkflow",
        EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
        1,
        RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE,
        cause,
    )

    private companion object {
        const val WORKFLOW_ID = "case-1"
    }
}
