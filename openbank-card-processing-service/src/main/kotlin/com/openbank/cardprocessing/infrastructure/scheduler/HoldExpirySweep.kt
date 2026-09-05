// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheduler

import com.openbank.cardprocessing.application.port.`in`.CardProcessingUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Releases holds no acquirer ever presented against.
 *
 * Without this, an approved authorisation that is never cleared holds the customer's money for ever
 * — a permanent freeze with no error anywhere, which nothing in a health probe can see.
 *
 * A **`suspend fun`**, for the reason in the outbox dispatcher's KDoc: a plain `@Scheduled` method
 * has no Vert.x context, so a reactive repository call from one throws `HR000068` and the sweep
 * silently never runs. A test that calls this method directly cannot catch that — the direct call
 * supplies the very context the scheduler does not — so the coverage that matters is a
 * `@TestProfile` that re-enables the scheduler and shrinks the cron.
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * A sweep that releases nothing is the NORMAL case here — most authorisations clear before they
 * expire — so "0 released" says nothing about whether the schedule is alive, and a sweep that has
 * silently stopped looks identical from outside: no exception escapes, no counter moves, and the
 * money stays frozen exactly as it would if the job had never been written.
 * [registerWorkflowLiveness] publishes the last-success age, which is what the ADR-0160 staleness
 * rule and `openbank-control-liveness-sentinel` read.
 *
 * `recordSuccess` is called only where the sweep actually returned. A heartbeat on the failure
 * branch would assert the very thing it exists to disprove.
 *
 * Registration hangs off [StartupEvent], not `@PostConstruct`: `@ApplicationScoped` is LAZY, so the
 * gauge would first appear when the cron first fires — and ABSENT is a different signal from stale,
 * which is the distinction the alert depends on.
 */
@ApplicationScoped
class HoldExpirySweep(
    private val useCase: CardProcessingUseCase,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.card-processing.hold-sweep-batch", defaultValue = "200")
    private val batchSize: Int,
) {
    private val log = Logger.getLogger(HoldExpirySweep::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "\${openbank.card-processing.hold-sweep-cron:0 */15 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "card-processing-hold-expiry",
    )
    suspend fun sweep() {
        val released = runCatching { useCase.releaseExpiredHolds(batchSize) }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.error("card hold expiry sweep failed", it) }
            .getOrDefault(0)
        if (released > 0) log.infof("released %d expired card hold(s)", released)
    }

    private companion object {
        const val WORKFLOW_NAME = "card-processing-hold-expiry"

        // Matches the default cron above (every 15 minutes). An operator who widens
        // `openbank.card-processing.hold-sweep-cron` widens the expected interval with it.
        const val EXPECTED_INTERVAL_MINUTES = 15L
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(EXPECTED_INTERVAL_MINUTES)
    }
}
