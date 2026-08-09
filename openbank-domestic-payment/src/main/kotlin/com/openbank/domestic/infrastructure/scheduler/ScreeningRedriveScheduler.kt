// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.domestic.infrastructure.scheduler

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
        if (stuck.isEmpty()) {
            liveness?.recordSuccess()
            return
        }

        log.infof("[screening-redrive] re-screening %d payment(s) held in RECEIVED", stuck.size)
        var hadFailure = false
        for (paymentId in stuck) {
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
                        WorkflowOptions.newBuilder()
                            .setTaskQueue(temporalTaskQueue)
                            // Distinct id per attempt: the original workflow already completed, and
                            // reusing its id would make the outcome depend on the server's id-reuse
                            // policy rather than on this decision.
                            .setWorkflowId("domestic-payment-$paymentId-redrive-${Instant.now(clock).toEpochMilli()}")
                            .build(),
                    )
                    WorkflowClient.start(stub::process, paymentId)
                }
                log.infof("[screening-redrive] re-drove payment %s", paymentId)
            } catch (@Suppress("TooGenericExceptionCaught") ex: Exception) {
                // One payment must not abort the sweep for the rest — the #846 shape.
                hadFailure = true
                log.warnf(ex, "[screening-redrive] could not re-drive payment %s", paymentId)
            }
        }
        if (!hadFailure) {
            liveness?.recordSuccess()
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_MIN_AGE_MINUTES = 10L
        const val DEFAULT_BATCH_LIMIT = 20
        const val SWEEP_INTERVAL_MINUTES = 15L
        const val WORKFLOW_NAME = "domestic-payment-screening-redrive"
    }
}
