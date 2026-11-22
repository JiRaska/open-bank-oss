// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.application.workflow

import com.openbank.transaction.domain.saga.SagaState
import io.temporal.common.VersioningBehavior
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import io.temporal.workflow.WorkflowVersioningBehavior
import java.util.UUID

/** A separate type preserves existing payment histories and permits patient branch recovery. */
@WorkflowInterface
interface BranchPaymentWorkflow {
    @WorkflowMethod(name = TYPE)
    fun execute(transactionId: UUID): SagaState

    companion object {
        const val TYPE = "BranchPayment"
    }
}

class BranchPaymentWorkflowImpl : BranchPaymentWorkflow {
    private val payment = PaymentWorkflowImpl(durableBranch = true)

    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    override fun execute(transactionId: UUID): SagaState = payment.execute(transactionId)
}
