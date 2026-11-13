// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.PaymentAdmissionMetricsPort
import com.openbank.domestic.application.workflow.AdmissionRun
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.PaymentHoldDecision
import com.openbank.domestic.application.workflow.domesticPaymentWorkflowId
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import io.quarkus.runtime.Startup
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Hands the decision on a hold's case to the payment's workflow (ADR-0374 D3).
 *
 * The signal goes to [domesticPaymentWorkflowId] with no run id, so it reaches the one open run of
 * the payment. When no run is open there are two different situations, and the payment's row tells
 * them apart:
 *
 *  - the payment is no longer HELD: the hold has ended, by decision or by expiry. Retrying could
 *    never help; this is returned as `false`, counted and logged, never swallowed.
 *  - the payment is still HELD: its run died. The decision is the case's verdict on a payment that
 *    is waiting for exactly that, so it is not dropped. A run is started for the payment and the
 *    decision delivered with it, in one request (signal-with-start), with the hold's original start
 *    in the run's memo so the expiry is unchanged.
 *
 * Any other failure propagates, so the record is retried and then dead-lettered.
 *
 * A signal is fire-and-forget. The workflow accepts it only for the hold's own case key; `true`
 * here means a run has it, not that the payment was released.
 *
 * Blocking: call it off the event loop.
 *
 * Created at startup, not on first use: its first use is on a worker thread of the consumer, and a
 * bean that reads configuration must not be created there.
 */
@Startup
@ApplicationScoped
class PaymentHoldResolutionService(
    private val workflowClient: WorkflowClient,
    private val metrics: PaymentAdmissionMetricsPort,
    private val paymentRepository: DomesticPaymentRepository,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val taskQueue: String,
) {
    private val log = Logger.getLogger(PaymentHoldResolutionService::class.java)

    private fun stillHeld(paymentId: UUID): DomesticPayment? = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { paymentRepository.findById(paymentId) }.asUni()
    }?.takeIf { it.status == DomesticPaymentStatus.HELD }

    fun resolve(paymentId: UUID, decision: PaymentHoldDecision, caseKey: String): Boolean {
        val delivered = try {
            workflowClient
                .newWorkflowStub(DomesticPaymentWorkflow::class.java, domesticPaymentWorkflowId(paymentId))
                .resolvePaymentHold(decision, caseKey)
            true
        } catch (_: WorkflowNotFoundException) {
            deliverToNewRun(paymentId, decision, caseKey)
        }
        metrics.recordResolution(release = decision == PaymentHoldDecision.RELEASE, delivered = delivered)
        if (delivered) {
            log.infof("Hold decision %s for payment %s delivered to its run", decision, paymentId)
        } else {
            log.warnf(
                "Hold decision %s for payment %s found no open run and the payment is not HELD; the hold " +
                    "had already ended and the payment's current status stands",
                decision,
                paymentId,
            )
        }
        return delivered
    }

    /** False when the payment is not HELD: there is no hold left to decide. */
    private fun deliverToNewRun(paymentId: UUID, decision: PaymentHoldDecision, caseKey: String): Boolean {
        val held = stillHeld(paymentId) ?: return false
        log.warnf("Hold decision %s for HELD payment %s found no open run; starting one with it", decision, paymentId)
        val stub = workflowClient.newWorkflowStub(
            DomesticPaymentWorkflow::class.java,
            // chainOn is irrelevant for a HELD payment: heldAt alone sends the run through admission.
            AdmissionRun.options(taskQueue, paymentId, chainOn = true, heldAt = held.heldAt ?: held.updatedAt)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build(),
        )
        // If a run appeared in the meantime (the reconciliation sweep), signal-with-start signals
        // that run and starts nothing.
        val request = workflowClient.newSignalWithStartRequest()
        request.add(stub::process, paymentId)
        request.add(stub::resolvePaymentHold, decision, caseKey)
        workflowClient.signalWithStart(request)
        return true
    }
}
