// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.usecase.CorrectionService
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration

/**
 * ADR-0344 D7: an approved correction has committed its new deal version; its delta journals are
 * owed to the ledger. Approval posts them at once; this pass finishes any the approval could not
 * (ledger down, day closed), so no approved correction stays silently unposted: each failure is
 * logged and withholds the liveness success.
 *
 * A `suspend fun`: a plain `@Scheduled` method has no Vert.x context (#2148).
 */
@ApplicationScoped
class CorrectionPostingScheduler(
    private val corrections: CorrectionService,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.treasury.corrections.posting.enabled", defaultValue = "true")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(CorrectionPostingScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.treasury.corrections.posting.interval:1h}",
        delayed = "\${openbank.treasury.corrections.posting.initial-delay:90s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-correction-posting",
    )
    suspend fun run() {
        if (!enabled) return
        runCatching { corrections.postPending() }
            .onSuccess { r ->
                r.failures.forEach { log.error("an approved correction could not post its delta journals", it) }
                if (r.failures.isEmpty()) liveness?.recordSuccess()
            }
            .onFailure { log.error("correction posting pass failed", it) }
    }

    private companion object {
        const val WORKFLOW_NAME = "treasury-correction-posting"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
