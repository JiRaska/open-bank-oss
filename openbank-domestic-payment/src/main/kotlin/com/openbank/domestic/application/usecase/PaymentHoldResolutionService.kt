// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.HoldDecisionRecord
import com.openbank.domestic.application.port.out.PaymentAdmissionMetricsPort
import com.openbank.domestic.application.port.out.PaymentAdmissionWrites
import com.openbank.domestic.application.workflow.DomesticPaymentRuns
import com.openbank.domestic.application.workflow.PaymentHoldPolicy
import com.openbank.domestic.domain.model.HoldDecision
import io.quarkus.runtime.Startup
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import io.temporal.client.WorkflowClient
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Takes the decision on a hold's case (ADR-0374 D3) and makes it durable where the hold is: on the
 * payment's row. That write is the whole decision. Whichever workflow run reaches the payment reads
 * it from there, so nothing is lost when the run that was waiting has died, has not started yet, or
 * dies right after being told.
 *
 * The row decides whether the decision counts, in one guarded write: it is accepted only for a
 * payment that is HELD with its case requested, for the hold's current review, once, and only
 * before the hold's expiry. A decision
 * that comes after the expiry is refused: a hold never expires into a release.
 *
 * Then the payment's run is nudged so it does not wait for its next check, and a HELD payment that
 * has no run is given one. Neither changes the outcome; they only make it prompt. A failure there
 * propagates, the record is retried, and the retry finds the decision already recorded.
 *
 * Blocking: call it off the event loop. Created at startup, not on first use: its first use is on a
 * worker thread of the consumer, and a bean that reads configuration must not be created there.
 */
@Startup
@ApplicationScoped
class PaymentHoldResolutionService(
    private val workflowClient: WorkflowClient,
    private val metrics: PaymentAdmissionMetricsPort,
    private val writes: PaymentAdmissionWrites,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val taskQueue: String,
) {
    private val log = Logger.getLogger(PaymentHoldResolutionService::class.java)

    private fun record(paymentId: UUID, review: Int, decision: HoldDecision): HoldDecisionRecord =
        VertxContextSupport.subscribeAndAwait {
            CoroutineScope(Dispatchers.Unconfined).async {
                writes.recordHoldDecision(paymentId, review, decision, Instant.now(clock), PaymentHoldPolicy.EXPIRY)
            }.asUni()
        }

    /** True when the payment has a hold this decision was, or already had been, offered to. */
    fun resolve(paymentId: UUID, review: Int, decision: HoldDecision): Boolean {
        val record = record(paymentId, review, decision)
        metrics.recordResolution(
            release = decision == HoldDecision.RELEASE,
            delivered =
            record == HoldDecisionRecord.ACCEPTED,
        )
        if (record == HoldDecisionRecord.NO_HOLD) {
            log.warnf(
                "Hold decision %s for payment %s: it has no hold to decide; its status stands",
                decision,
                paymentId,
            )
            return false
        }
        log.infof("Hold decision %s for payment %s: %s", decision, paymentId, record)
        // Whatever the record said, a HELD payment needs a run to end its hold: to apply the
        // decision, or to reject a hold that has expired.
        val runs = DomesticPaymentRuns(workflowClient, taskQueue)
        if (!runs.nudgeHold(paymentId) && runs.recoverHeld(paymentId)) {
            log.warnf("HELD payment %s had no open run when its case was decided; started one", paymentId)
        }
        return true
    }
}
