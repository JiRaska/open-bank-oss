// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.sca.application.usecase.DecisionEvidenceRetention
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Daily purge of signed device-decision evidence past its retention period
 * ([DecisionEvidenceRetention], `openbank.sca.decision-retention-days`).
 *
 * MUST stay a `suspend fun`: a plain `@Scheduled` method runs on a bare executor thread with no
 * Vert.x context, so reactive Panache throws `HR000068` and the job silently does nothing
 * (#2148, #2187). `DecisionEvidencePurgeSchedulerIT` drives the real cron for that reason.
 *
 * `openbank.sca.decision-purge.enabled` switches it off (off under `%test`, so unrelated tests keep
 * their rows). When disabled, liveness is not registered at all: an absent heartbeat is the honest
 * signal for a job that is not meant to run, where a recorded success would claim a purge that did
 * not happen. Liveness and the purged-rows counter are registered from [StartupEvent], because
 * `@ApplicationScoped` is lazy and a registration in the constructor would not exist until the
 * first tick.
 */
@ApplicationScoped
class DecisionEvidencePurgeScheduler(
    private val retention: DecisionEvidenceRetention,
    private val domainMetrics: DomainMetrics,
    private val registry: MeterRegistry,
    @ConfigProperty(name = "openbank.sca.decision-purge.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {

    private val log: Logger = Logger.getLogger(DecisionEvidencePurgeScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    private var purged: Counter? = null

    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (!enabled) {
            log.warn("SCA decision-evidence purge is DISABLED (openbank.sca.decision-purge.enabled=false)")
            return
        }
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        purged = Counter.builder(PURGED_METRIC)
            .description("Signed SCA device-decision rows deleted after their retention period")
            .register(registry)
    }

    @Scheduled(
        cron = "\${openbank.sca.decision-purge.cron:0 30 3 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "sca-decision-evidence-purge",
    )
    suspend fun purge() {
        if (!enabled) return
        runCatching { retention.purgeExpired() }
            .onSuccess { deleted ->
                purged?.increment(deleted.toDouble())
                liveness?.recordSuccess()
                if (deleted > 0) log.infof("purged %d SCA decision-evidence row(s) past retention", deleted)
            }
            .onFailure { log.error("SCA decision-evidence purge failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "sca-decision-evidence-purge"
        const val PURGED_METRIC = "openbank.sca.decision.evidence.purged"

        // Matches the default daily cron. An operator who widens the cron widens this with it.
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
