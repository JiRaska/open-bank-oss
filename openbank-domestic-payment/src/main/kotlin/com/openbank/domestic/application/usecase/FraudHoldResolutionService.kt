// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudReviewDecision
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Hands an analyst's decision on a fraud hold to the payment's waiting workflow (#4403).
 *
 * A run that no longer exists (it already timed out into rejection, or was started by the screening
 * re-drive under a suffixed id) is not an error: retrying could never help, so it is counted as
 * [FraudHoldDelivery.NOT_WAITING] and the hold's fail-closed outcome stands. Any other failure
 * propagates, so the record is retried and then dead-lettered.
 *
 * A signal is fire-and-forget: a run that is not yet holding, or that holds for a different case,
 * ignores it and counts it in `openbank_domestic_fraud_hold_signal_ignored`; this service still
 * records DELIVERED, meaning only that a run existed.
 */
@ApplicationScoped
class FraudHoldResolutionService(
    private val workflowClient: WorkflowClient,
    private val metrics: FraudEnforcementMetricsPort,
) {
    private val log = Logger.getLogger(FraudHoldResolutionService::class.java)

    fun resolve(paymentId: UUID, decision: FraudReviewDecision, caseIdempotencyKey: String): FraudHoldDelivery {
        val delivery = try {
            workflowClient
                .newWorkflowStub(DomesticPaymentWorkflow::class.java, "domestic-payment-$paymentId")
                .resolveFraudHold(decision, caseIdempotencyKey)
            FraudHoldDelivery.DELIVERED
        } catch (_: WorkflowNotFoundException) {
            FraudHoldDelivery.NOT_WAITING
        }
        metrics.recordHoldResolution(decision, delivery)
        log.infof("Fraud hold decision %s for payment %s: %s", decision, paymentId, delivery)
        return delivery
    }
}
