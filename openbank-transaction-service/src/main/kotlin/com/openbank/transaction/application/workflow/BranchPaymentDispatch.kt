// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.application.workflow

import com.openbank.libs.temporal.TemporalConfig
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** The original caller and every recovery replica share one Temporal operation id. */
@ApplicationScoped
class BranchPaymentDispatch(private val temporal: TemporalConfig, private val client: Provider<WorkflowClient>) {
    suspend fun start(id: UUID) = withContext(Dispatchers.IO) {
        val workflow = client.get().newUntypedWorkflowStub(
            BranchPaymentWorkflow.TYPE,
            WorkflowOptions.newBuilder().setTaskQueue(temporal.taskQueue()).setWorkflowId("payment-$id")
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build(),
        )
        try {
            workflow.start(id)
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // An existing running or completed workflow must never dispatch a second payment.
        }
        Unit
    }
}
