// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentRuns
import com.openbank.domestic.application.workflow.PaymentAdmissionConfig
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.temporal.client.WorkflowClient
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
        val runs = DomesticPaymentRuns(workflowClient, temporalTaskQueue)
        // Read here, on the scheduler's own thread, and not inside the blocking call below.
        // The one boundary: with the chain off, re-driving RECEIVED payments reads, writes and counts
        // exactly as main does. Only the chain-on path and HELD payments use `run_checked_at`.
        val chainOn = admission.enabled
        var hadFailure = if (chainOn) redriveChainOn(runs, minAge) else redriveAsMain(runs, minAge)
        val held = paymentRepository.findHeldToReconcile(minAge, HELD_BATCH_LIMIT)
        // ADR-0374: a HELD payment lives only as long as its workflow run does. The run is what
        // re-evaluates it, applies its case's decision and expires it, so a HELD payment whose run
        // is gone (failed, terminated, timed out) would stay HELD for good. Every tick offers a run
        // to the least recently checked holds; Temporal refuses the offer while a run is open, which
        // is the normal case, costs one call and is not logged.
        for (ref in held) {
            when (offerRun(ref.paymentId) { runs.recoverHeld(ref.paymentId) }) {
                true -> {
                    recoveredHolds?.increment()
                    log.warnf("[screening-redrive] HELD payment %s had no open run; started one", ref.paymentId)
                }
                false -> Unit
                null -> hadFailure = true
            }
        }
        if (!hadFailure) {
            liveness?.recordSuccess()
        }
    }

    /**
     * Main's re-drive, unchanged: the attempt is counted BEFORE it is made, so a payment whose
     * re-drive keeps failing (Temporal unreachable, a run stuck open) spends its budget and becomes
     * visible as exhausted (`countRedriveExhausted`) instead of being retried forever.
     */
    private suspend fun redriveAsMain(runs: DomesticPaymentRuns, minAge: Instant): Boolean {
        val stuck = paymentRepository.findRedrivable(maxAttempts, minAge, batchLimit)
        if (stuck.isNotEmpty()) {
            log.infof("[screening-redrive] re-screening %d payment(s) held in RECEIVED", stuck.size)
        }
        var hadFailure = false
        for (paymentId in stuck) {
            paymentRepository.recordRedriveAttempt(paymentId)
            try {
                val started = withContext(Dispatchers.IO) { runs.redrive(paymentId, chainOn = false) }
                if (started) {
                    log.infof("[screening-redrive] re-drove payment %s", paymentId)
                } else {
                    log.infof("[screening-redrive] payment %s still has an open run; not re-driven", paymentId)
                }
            } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                hadFailure = true
                log.warnf(ex, "[screening-redrive] could not re-drive payment %s", paymentId)
            }
        }
        return hadFailure
    }

    /** The chain-on re-drive: payments least recently offered a run first; only a started run counts. */
    private suspend fun redriveChainOn(runs: DomesticPaymentRuns, minAge: Instant): Boolean {
        val stuck = paymentRepository.findRedrivableLeastRecentlyChecked(maxAttempts, minAge, batchLimit)
        if (stuck.isNotEmpty()) {
            log.infof("[screening-redrive] re-screening %d payment(s) held in RECEIVED", stuck.size)
        }
        var hadFailure = false
        for (paymentId in stuck) {
            when (offerRun(paymentId) { runs.redrive(paymentId, chainOn = true) }) {
                // The budget counts re-drives, and a re-drive is a run that was started. An offer
                // Temporal refused because a run is still open spends nothing.
                true -> {
                    paymentRepository.recordRedriveAttempt(paymentId)
                    log.infof("[screening-redrive] re-drove payment %s", paymentId)
                }
                false -> log.infof("[screening-redrive] payment %s still has an open run; not re-driven", paymentId)
                null -> hadFailure = true
            }
        }
        return hadFailure
    }

    /**
     * Offer the payment a run. True when one was started, false when one was already open, null
     * when the offer itself failed. The check is noted BEFORE the offer, so a payment whose offer
     * kills the sweep goes to the back of the queue instead of heading every tick.
     */
    private suspend fun offerRun(paymentId: UUID, start: () -> Boolean): Boolean? {
        paymentRepository.markRunChecked(paymentId, Instant.now(clock))
        return try {
            // Temporal's start is a BLOCKING gRPC call. This method is `suspend`, so Quarkus
            // dispatches it on a duplicated Vert.x context — blocking there stalls the event
            // loop, and with ConcurrentExecution.SKIP one stalled tick wedges every later one.
            // Measured: the sweep re-drove exactly one payment and then never ran again.
            withContext(Dispatchers.IO) { start() }
        } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
            // One payment must not abort the sweep for the rest — the #846 shape.
            log.warnf(ex, "[screening-redrive] could not offer payment %s a run", paymentId)
            null
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_MIN_AGE_MINUTES = 10L
        const val DEFAULT_BATCH_LIMIT = 20

        /**
         * HELD payments offered a run per tick. A constant: each offer is one cheap call that
         * Temporal refuses while a run is open, and nothing about a deployment changes that.
         */
        const val HELD_BATCH_LIMIT = 200

        /** A HELD payment was found with no open run and given one. Any increment is a run that died. */
        const val RECOVERED_HOLDS_METRIC = "openbank.payment.admission.hold.runs.recovered"
        const val SWEEP_INTERVAL_MINUTES = 15L
        const val WORKFLOW_NAME = "domestic-payment-screening-redrive"
    }
}
