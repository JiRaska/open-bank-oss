// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import java.util.UUID

/**
 * The one place a workflow run of a domestic payment is started or woken, and so the one place the
 * task queue of a run is chosen ([AdmissionRun]): the first run of a payment, a re-drive of a
 * RECEIVED one, the recovery of a HELD one, and the nudge that tells a run its hold changed.
 *
 * Every call here blocks on Temporal: call it off the event loop.
 */
class DomesticPaymentRuns(private val workflowClient: WorkflowClient, private val railQueue: String) {

    /** The first run of a new payment. [chainOn] is the rail's admission switch as the caller read it. */
    fun start(paymentId: UUID, chainOn: Boolean) {
        WorkflowClient.start(stub(paymentId, chainOn, reuse = false)::process, paymentId)
    }

    /**
     * Another run for a payment still RECEIVED, unless one is open. True when a run was started.
     *
     * The SAME workflow id as the first run, with the reuse policy stated rather than inherited: a
     * new run may start only once the previous one has closed. A hold decision is addressed to
     * this id and so always reaches the current run.
     */
    fun redrive(paymentId: UUID, chainOn: Boolean): Boolean = startUnlessOpen(paymentId, chainOn)

    /**
     * A run for a payment that is HELD and has none. True when a run was started. Always an
     * admission run, whatever the switch says: turning the chain off must not strand its holds.
     */
    fun recoverHeld(paymentId: UUID): Boolean = startUnlessOpen(paymentId, chainOn = true)

    /** Tell the payment's open run that its hold changed. False when no run is open. */
    fun nudgeHold(paymentId: UUID): Boolean = try {
        workflowClient
            .newWorkflowStub(DomesticPaymentWorkflow::class.java, domesticPaymentWorkflowId(paymentId))
            .holdChanged()
        true
    } catch (_: WorkflowNotFoundException) {
        false
    }

    private fun startUnlessOpen(paymentId: UUID, chainOn: Boolean): Boolean = try {
        WorkflowClient.start(stub(paymentId, chainOn, reuse = true)::process, paymentId)
        true
    } catch (_: WorkflowExecutionAlreadyStarted) {
        false
    }

    private fun stub(paymentId: UUID, chainOn: Boolean, reuse: Boolean): DomesticPaymentWorkflow {
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(if (chainOn) AdmissionRun.queue(railQueue) else railQueue)
            .setWorkflowId(domesticPaymentWorkflowId(paymentId))
        if (reuse) {
            options
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
        }
        return workflowClient.newWorkflowStub(DomesticPaymentWorkflow::class.java, options.build())
    }
}
