// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudReviewDecision
import com.openbank.domestic.application.workflow.domesticPaymentWorkflowId
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Hands an analyst's decision on a fraud hold to the payment's waiting workflow (#4403).
 *
 * The signal goes to [domesticPaymentWorkflowId] with no run id, so Temporal routes it to the
 * CURRENT run of the payment: the first run or a screening re-drive, which reuses the same id
 * (#15198 review). There is never more than one open run per payment, so this cannot reach a stale one.
 *
 * When no run is open (the hold already ended by decision or timeout) retrying could never help, so
 * the outcome is [FraudHoldDelivery.NOT_WAITING]: returned, counted in
 * `openbank.fraud.enforcement.hold.resolutions` (NOT_WAITING) and logged at WARN, never
 * swallowed. Nothing is left pending behind it: with no open run there is no hold to time out.
 * Any other failure propagates, so the record is retried and then dead-lettered.
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
                .newWorkflowStub(DomesticPaymentWorkflow::class.java, domesticPaymentWorkflowId(paymentId))
                .resolveFraudHold(decision, caseIdempotencyKey)
            FraudHoldDelivery.DELIVERED
        } catch (_: WorkflowNotFoundException) {
            FraudHoldDelivery.NOT_WAITING
        }
        metrics.recordHoldResolution(decision, delivery)
        if (delivery == FraudHoldDelivery.NOT_WAITING) {
            log.warnf(
                "Fraud hold decision %s for payment %s found no open run; the hold had already ended " +
                    "and the payment's current status stands — check it before re-deciding",
                decision,
                paymentId,
            )
        } else {
            log.infof("Fraud hold decision %s for payment %s: %s", decision, paymentId, delivery)
        }
        return delivery
    }
}
