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
     *
     * Accepted only while the run is actually holding, and only for the case the rail itself opened:
     * [caseIdempotencyKey] must equal [fraudReviewCaseKey] for this payment. A signal that arrives
     * before the hold, after it ended, or names another case is ignored and counted, so a decision
     * cannot be pre-armed and a case someone else created cannot release the payment.
     */
    @SignalMethod
    fun resolveFraudHold(decision: FraudReviewDecision, caseIdempotencyKey: String?)

    /**
     * ADR-0374 D3: release or reject a payment the admission chain HELD. With no signal before the
     * hold expires the payment is REJECTED; a hold never expires into a release.
     *
     * Accepted only while the run is waiting on a hold whose case was requested, and only for that
     * case: [caseKey] must equal [PaymentHoldPolicy.caseKey] for this payment. A signal that
     * arrives before the hold, during an automatic re-evaluation, after the hold ended, or that
     * names another case is ignored and counted.
     */
    @SignalMethod
    fun resolvePaymentHold(decision: PaymentHoldDecision, caseKey: String?)
}

/**
 * The ONE workflow id every run for [paymentId] carries: the first start and every screening re-drive.
 * A fraud-hold decision is signalled to this id with no run id, which Temporal routes to the CURRENT
 * run, so it reaches a re-driven hold exactly as it reaches the first one. A per-attempt id left the
 * decision addressed to a closed run while the live one timed out into REJECTED. One id also means
 * at most one open run per payment: a re-drive while a run is still open is refused by Temporal.
 */
fun domesticPaymentWorkflowId(paymentId: UUID): String = "domestic-payment-$paymentId"
