// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

@WorkflowInterface
interface DomesticPaymentWorkflow {
    @WorkflowMethod
    fun process(paymentId: UUID): DomesticPaymentStatus

    /**
     * #4403: release or reject a payment held by the fraud gate. With no signal before the hold
     * timeout the payment is REJECTED; a hold never expires into a pass.
     */
    @SignalMethod
    fun resolveFraudHold(decision: FraudReviewDecision)
}
