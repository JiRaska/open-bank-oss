// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.AdmissionRun
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.PaymentAdmissionConfig
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Re-screens payments left holding on an unavailable sanctions check (#3266).
 *
 * ### Why this exists
 *
 * `screenPayment` returns `REVIEW` when sanctions screening is unreachable, and the workflow then
 * returns `RECEIVED` and **completes**. That hold is correct — on an outage a `HIT` is unknowable,
 * and `applySddPolicy` only ever downgrades `REVIEW`, never `BLOCK`, so releasing would mean
 * releasing un-screened. What was missing is the way out: nothing re-opens the hold. The AML case is
 * opened `OPEN`, and this service has no messaging consumer, so deciding that case cannot release
 * the payment. A dependency outage lasting minutes therefore became a permanent strand — one payment
 * sat for hours, six others for weeks, while every alert stayed green.
 *
 * This sweep re-runs the **workflow**, so the payment is screened again and only advances if the
 * screen now clears. It never releases anything on its own, and it is not a retry of the payment —
 * `submitScheme` and `settlePayment` are reached only through the same gates as a first attempt.
 *
 * ### Why it is safe only now
 *
 * A re-drive reuses the idempotency keys `<paymentId>:debtor` / `:creditor`. Before #3264 a partially
 * completed screen (debtor stored, creditor not) made the replay collide with
 * `sanctions_checks_idempotency_key_key` and return 500, so the re-drive could not have worked. With
 * that merged, the debtor replays its stored verdict and the creditor is screened fresh.
 *
 * ### Bounds
 *
 * Three, because an unbounded sweep would re-screen a genuinely held payment forever:
 * [maxAttempts] on the row's own counter, [minAgeMinutes] so a payment still mid-flight is never
 * touched, and [batchLimit] per tick. A payment that exhausts its attempts stays put and is now
 * visible — `openbank_domestic_payments_non_terminal_oldest_age_seconds` (#3273) alerts on it.
 *
 * `suspend`, never `runBlocking` (#2148): Quarkus invokes a plain `@Scheduled` method on a bare
 * executor thread with no Vert.x context, so the first reactive Panache query would throw HR000068
 * before the per-item try/catch and the sweep would silently never run.
 */
@ApplicationScoped
class ScreeningRedriveScheduler {

    @Inject
    lateinit var paymentRepository: DomesticPaymentRepository

    @Inject
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var clock: Clock

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @ConfigProperty(name = "openbank.domestic.screening-redrive.enabled", defaultValue = "true")
    var enabled: Boolean = true

    @ConfigProperty(name = "openbank.domestic.screening-redrive.max-attempts", defaultValue = "5")
    var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS

    @ConfigProperty(name = "openbank.domestic.screening-redrive.min-age-minutes", defaultValue = "10")
    var minAgeMinutes: Long = DEFAULT_MIN_AGE_MINUTES

    @ConfigProperty(name = "openbank.domestic.screening-redrive.batch-limit", defaultValue = "20")
    var batchLimit: Int = DEFAULT_BATCH_LIMIT

    @ConfigProperty(name = "openbank.domestic.screening-redrive.held-batch-limit", defaultValue = "200")
    var heldBatchLimit: Int = DEFAULT_HELD_BATCH_LIMIT

    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    lateinit var temporalTaskQueue: String

    /** The rail's admission switch, as the starter of a run reads it (see AdmissionRun). */
    @Inject
    var admission: PaymentAdmissionConfig = PaymentAdmissionConfig(enabled = false)

    @Inject
    var registry: Instance<MeterRegistry>? = null
    private val recoveredHolds: Counter? by lazy {
        registry?.takeIf { it.isResolvable }?.get()?.counter(RECOVERED_HOLDS_METRIC)
    }

    private val log = Logger.getLogger(ScreeningRedriveScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofMinutes(SWEEP_INTERVAL_MINUTES))
    }

    @Scheduled(
        every = "{openbank.domestic.screening-redrive.interval:15m}",
        delayed = "{openbank.domestic.screening-redrive.initial-delay:2m}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sweep() {
        if (!enabled) {
            log.debug("[screening-redrive] disabled — skipping")
            return
        }
        val minAge = Instant.now(clock).minus(Duration.ofMinutes(minAgeMinutes))
        val stuck = paymentRepository.findRedrivable(maxAttempts, minAge, batchLimit)
        val held = paymentRepository.findHeldToReconcile(minAge, heldBatchLimit)
        if (stuck.isNotEmpty()) {
            log.infof("[screening-redrive] re-screening %d payment(s) held in RECEIVED", stuck.size)
        }
        var hadFailure = false
        for (paymentId in stuck) {
            if (!ensureRun(paymentId, heldAt = null)) hadFailure = true
        }
        // ADR-0374: a HELD payment lives only as long as its workflow run does. The run is what
        // re-evaluates it, waits for its case and expires it, so a HELD payment whose run is gone
        // (failed, terminated, timed out) would stay HELD for good. Every tick offers a run to the
        // least recently checked holds; Temporal refuses the offer while a run is open, which is the
        // normal case and costs one call.
        for (ref in held) {
            if (!ensureRun(ref.paymentId, heldAt = ref.heldAt)) hadFailure = true
        }
        if (!hadFailure) {
            liveness?.recordSuccess()
        }
    }

    /**
     * Start a run for the payment unless one is open. [heldAt] is set for a payment that is HELD:
     * its run goes through admission whatever the switch says and keeps the first hold's expiry.
     * Returns false only when the attempt itself failed.
     */
    private suspend fun ensureRun(paymentId: UUID, heldAt: Instant?): Boolean {
        // Counted BEFORE the attempt: a re-drive that dies mid-flight must still consume its
        // budget, or a payment that reliably kills the sweep is retried forever.
        paymentRepository.recordRedriveAttempt(paymentId)
        try {
            // Temporal's start is a BLOCKING gRPC call. This method is `suspend`, so Quarkus
            // dispatches it on a duplicated Vert.x context — blocking there stalls the event
            // loop, and with ConcurrentExecution.SKIP one stalled tick wedges every later one.
            // Measured: the sweep re-drove exactly one payment and then never ran again.
            withContext(Dispatchers.IO) {
                val stub = workflowClient.newWorkflowStub(
                    DomesticPaymentWorkflow::class.java,
                    // The SAME id as the first run, with the reuse policy stated rather than
                    // inherited: a new run may start only once the previous one has closed.
                    // A fraud-hold decision is signalled to this id and so always reaches the
                    // current run; a per-attempt id stranded it on the closed one and let the
                    // re-driven hold time out into REJECTED.
                    AdmissionRun.options(temporalTaskQueue, paymentId, admission.enabled, heldAt)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                        .build(),
                )
                WorkflowClient.start(stub::process, paymentId)
            }
            if (heldAt == null) {
                log.infof("[screening-redrive] re-drove payment %s", paymentId)
            } else {
                recoveredHolds?.increment()
                log.warnf("[screening-redrive] HELD payment %s had no open run; started one", paymentId)
            }
        } catch (_: WorkflowExecutionAlreadyStarted) {
            // A run for this payment is still open — typically a fraud hold awaiting an analyst,
            // during which the payment stays RECEIVED. Starting a second run would re-screen,
            // re-score and hold the payment twice in parallel; leaving it alone is correct.
            // The attempt counted above is spent without a re-drive, and that is harmless: a run
            // that reached the fraud hold has already cleared screening, so it ends VALIDATED or
            // REJECTED and never returns the payment to RECEIVED for this sweep to need.
            // For a HELD payment this is every healthy hold on every tick, so it is not logged.
            if (heldAt == null) {
                log.infof("[screening-redrive] payment %s still has an open run; not re-driven", paymentId)
            }
        } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
            // One payment must not abort the sweep for the rest — the #846 shape.
            log.warnf(ex, "[screening-redrive] could not re-drive payment %s", paymentId)
            return false
        }
        return true
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_MIN_AGE_MINUTES = 10L
        const val DEFAULT_BATCH_LIMIT = 20
        const val DEFAULT_HELD_BATCH_LIMIT = 200

        /** A HELD payment was found with no open run and given one. Any increment is a run that died. */
        const val RECOVERED_HOLDS_METRIC = "openbank.payment.admission.hold.runs.recovered"
        const val SWEEP_INTERVAL_MINUTES = 15L
        const val WORKFLOW_NAME = "domestic-payment-screening-redrive"
    }
}
