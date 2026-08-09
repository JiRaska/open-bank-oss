// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.usecase.SavingsProposalService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Sweeps PENDING withdrawal proposals whose window has closed into EXPIRED (ADR-0232 D8 / AC8).
 *
 * MUST be a `suspend fun`. A plain `@Scheduled` method carries no Vert.x context — Quarkus invokes
 * it on a bare executor-thread — so a body wrapping the reactive Panache repository in
 * `runBlocking` throws `HR000068` and the job aborts having done nothing, silently. Five schedulers
 * in this fleet had never once run for exactly that reason (#2148, #2187), which is also why
 * `SavingsProposalExpirySchedulerVertxContextIT` drives the REAL cron from a test profile instead
 * of calling this method directly: a direct call supplies the very context the scheduler does not,
 * so it passes against broken code.
 *
 * ## Liveness heartbeat (ADR-0237)
 *
 * `runCatching` swallows a failed tick so one bad run cannot kill the schedule — which means a
 * permanently broken sweep looks exactly like a healthy quiet one from the outside: no exception
 * escapes, no metric moves, and "0 proposals expired" is the normal case. [registerWorkflowLiveness]
 * publishes the last-success age so the ADR-0160 staleness rule and
 * `openbank-control-liveness-sentinel` can see a schedule that stopped succeeding.
 * [WorkflowLivenessRecorder.recordSuccess] is called only where `expireStale` actually returned,
 * never on the failure branch — a heartbeat on the failure path asserts the very thing it exists to
 * disprove.
 *
 * Registration hangs off [StartupEvent], not `@PostConstruct`: `@ApplicationScoped` is LAZY, so a
 * `@PostConstruct` here would first run when the cron first fires, leaving the gauge absent until
 * then — and absent is not the same signal as stale.
 */
@ApplicationScoped
class SavingsProposalExpiryScheduler(
    private val proposalService: SavingsProposalService,
    private val domainMetrics: DomainMetrics,
) {

    private val log: Logger = Logger.getLogger(SavingsProposalExpiryScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "\${openbank.savings.proposal-expiry-cron:0 */10 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "savings-proposal-expiry",
    )
    suspend fun sweep() {
        val expired = runCatching { proposalService.expireStale() }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.error("savings withdrawal proposal expiry sweep failed", it) }
            .getOrDefault(0)
        if (expired > 0) log.infof("expired %d stale savings withdrawal proposal(s)", expired)
    }

    private companion object {
        const val WORKFLOW_NAME = "account-savings-proposal-expiry"

        // Matches the default cron above (every 10 minutes). An operator who widens
        // `openbank.savings.proposal-expiry-cron` widens the expected interval with it.
        const val EXPECTED_INTERVAL_MINUTES = 10L
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(EXPECTED_INTERVAL_MINUTES)
    }
}
