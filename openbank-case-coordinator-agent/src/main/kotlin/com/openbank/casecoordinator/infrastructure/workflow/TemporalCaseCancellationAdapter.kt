// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.workflow

import com.openbank.casecoordinator.application.port.out.TemporalCaseCancellationPort
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowFailedException
import io.temporal.failure.CanceledFailure
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TemporalCaseCancellationAdapter(private val workflowClient: WorkflowClient) : TemporalCaseCancellationPort {
    override fun cancelAndAwait(workflowId: String) {
        val stub = workflowClient.newUntypedWorkflowStub(workflowId)
        stub.cancel()
        try {
            stub.getResult(Void::class.java)
            error("workflow $workflowId completed instead of acknowledging cancellation")
        } catch (failure: WorkflowFailedException) {
            if (failure.cause !is CanceledFailure) throw failure
        }
    }
}
