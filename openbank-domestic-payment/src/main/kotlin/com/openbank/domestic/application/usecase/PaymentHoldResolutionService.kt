// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.PaymentAdmissionMetricsPort
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.PaymentHoldDecision
import com.openbank.domestic.application.workflow.domesticPaymentWorkflowId
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Hands the decision on a hold's case to the payment's waiting workflow (ADR-0374 D3).
 *
 * The signal goes to [domesticPaymentWorkflowId] with no run id, so it reaches the one open run of
 * the payment. When no run is open the hold has already ended, by decision or by expiry, and
 * retrying could never help: that is returned as `false`, counted and logged, never swallowed. Any
 * other failure propagates, so the record is retried and then dead-lettered.
 *
 * A signal is fire-and-forget. The workflow accepts it only while it is waiting on this hold and
 * only for the hold's own case key; `true` here means a run existed, not that it released.
 */
@ApplicationScoped
class PaymentHoldResolutionService(
    private val workflowClient: WorkflowClient,
    private val metrics: PaymentAdmissionMetricsPort,
) {
    private val log = Logger.getLogger(PaymentHoldResolutionService::class.java)

    fun resolve(paymentId: UUID, decision: PaymentHoldDecision, caseKey: String): Boolean {
        val delivered = try {
            workflowClient
                .newWorkflowStub(DomesticPaymentWorkflow::class.java, domesticPaymentWorkflowId(paymentId))
                .resolvePaymentHold(decision, caseKey)
            true
        } catch (_: WorkflowNotFoundException) {
            false
        }
        metrics.recordResolution(release = decision == PaymentHoldDecision.RELEASE, delivered = delivered)
        if (delivered) {
            log.infof("Hold decision %s for payment %s delivered to its run", decision, paymentId)
        } else {
            log.warnf(
                "Hold decision %s for payment %s found no open run; the hold had already ended and the " +
                    "payment's current status stands",
                decision,
                paymentId,
            )
        }
        return delivered
    }
}
