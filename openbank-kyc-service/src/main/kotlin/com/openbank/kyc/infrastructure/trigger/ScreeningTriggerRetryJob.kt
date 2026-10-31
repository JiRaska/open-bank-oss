// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.trigger

import com.openbank.kyc.application.ScreeningTriggerService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Re-attempts ADR-0256 screening triggers whose coordinator case-open failed (429/503/timeout)
 * and whose backoff has elapsed — the retry half of the durable failure path. Without this job
 * a coordinator outage would convert every list-change trigger into a parked row the moment the
 * consumer's retries exhausted; with it the loop closes as soon as the coordinator recovers.
 *
 * Terminal (exhausted) rows are never re-claimed: their KYC_COORDINATOR_OPEN_FAILED outbox row
 * is the operator hand-off, per the ADR-0256 contract.
 *
 * The method MUST stay a `suspend fun` — a plain @Scheduled body has no Vert.x context and every
 * reactive Panache call fails with HR000068 (#2148, #2187).
 */
@ApplicationScoped
class ScreeningTriggerRetryJob(
    private val service: ScreeningTriggerService,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.kyc.screening-triggers.retry-enabled", defaultValue = "true")
    private val retryEnabled: Boolean,
    @ConfigProperty(name = "openbank.kyc.screening-triggers.retry-batch-size", defaultValue = "100")
    private val batchSize: Int,
    private val domainMetrics: DomainMetrics,
) {

    private val log = Logger.getLogger(ScreeningTriggerRetryJob::class.java)
    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Boot-seeded ADR-0237 heartbeat: "nothing to retry" is both the healthy quiet state and what
     * a schedule that stopped firing looks like from outside, so the job publishes last-success
     * age. A disabled job records nothing — it has not proved anything ran.
     */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    // TooGenericExceptionCaught: one bad tick must not kill the cron — an un-claimed row is still
    // OPEN_FAILED and still due on the next tick by definition.
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(
        every = "{openbank.kyc.screening-triggers.retry-interval:1m}",
        identity = "kyc-screening-trigger-retry",
        concurrentExecution = SKIP,
    )
    suspend fun retryFailedTriggers() {
        if (!retryEnabled) return
        try {
            val opened = service.retryFailedTriggers(Instant.now(clock), batchSize)
            liveness?.recordSuccess()
            if (opened > 0) {
                log.infof("kyc.screening-trigger.retry opened=%d", opened)
            }
        } catch (err: Exception) {
            log.errorf(err, "kyc.screening-trigger.retry FAILED")
        }
    }

    private companion object {
        const val WORKFLOW_NAME = "kyc.screening-trigger.retry"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
