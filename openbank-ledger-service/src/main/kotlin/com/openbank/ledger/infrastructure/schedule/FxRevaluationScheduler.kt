// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.lock.ClusterLock
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily job that marks foreign FX positions to the ČNB fixing (ADR-0046) at 15:00 Europe/Prague —
 * after fx-service has ingested the day's fixing (~14:40). The revaluation is idempotent per
 * business day (`fx-reval-{date}` idempotency key), so a concurrent-revalue race loser gets the
 * winner's *posting* back safely — but it still separately publishes a second, non-outboxed
 * `openbank.ledger.fx.revalued` event and logs "posted" (#1201, L-12). `concurrentExecution =
 * SKIP` only stops in-JVM overlap; an Argo Rollouts canary window runs the old and new pod
 * simultaneously for the whole rollout, and the 15:00 run landing inside a deploy window is not
 * hypothetical — deploys happen during business hours. [ClusterLock.tryRunExclusively] wraps the
 * run in a transaction-scoped advisory lock so only one pod actually revalues and publishes per
 * day; the losing pod's tick is a no-op. A missed or repeated run is still harmless independent
 * of this — the manual `POST /api/v1/ledger/fx-revaluation` covers backfill — this only removes
 * the double-publish.
 */
@ApplicationScoped
class FxRevaluationScheduler(
    private val useCase: FxRevaluationUseCase,
    private val clusterLock: ClusterLock,
    private val domainMetrics: DomainMetrics,
) {
    private val log: Logger = Logger.getLogger(FxRevaluationScheduler::class.java)
    private val zone: ZoneId = ZoneId.of("Europe/Prague")

    // Nullable, not `lateinit`: the gauge is a diagnostic, and a money-path job must never fail
    // because its observability wiring was not initialised. `lateinit` turns a missed StartupEvent
    // into an UninitializedPropertyAccessException thrown from the middle of the run.
    private var liveness: WorkflowLivenessRecorder? = null

    // ADR-0160 mechanism 3. Registered once at startup (CDI beans are singletons), not per-run —
    // matches DomainMetrics.registerOutboxBacklog's "call once" contract and the one pre-existing
    // adopter, StandingOrderExecutionScheduler. Before this, a revaluation that stopped that stopped
    // running left NO runtime signal at all: this job has no metric, no watchdog and no alert rule of any
    // kind, so success and failure both ended in a log line (#2239).
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    // `suspend`, never `runBlocking` (#2187, the fleet sweep of #2148). Quarkus invokes a plain
    // @Scheduled method on a bare `executor-thread`, which carries no Vert.x context, so
    // `runBlocking { clusterLock.tryRunExclusively(…) }` ran [PostgresClusterLock]'s
    // `Panache.withTransaction` — the FIRST reactive call, and it sits *outside* the inner
    // try/catch below — off the event loop and threw `HR000068: This method should exclusively be
    // invoked from a Vert.x EventLoop thread`. Every tick aborted before `revalue` was ever
    // reached, so no FX position was ever marked to the fixing. A suspending @Scheduled method is
    // dispatched by Quarkus on a proper (duplicated) Vert.x context instead.
    //
    // The cron is a config expression (same default as before) purely so an IT can shrink it and
    // drive the *real* scheduler dispatch — calling this method directly supplies a context the
    // scheduler does not, and would pass against the broken code.
    @Scheduled(
        cron = "{openbank.ledger.fx-revaluation.cron:0 0 15 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun revalueDaily() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            try {
                val result = useCase.revalue(RevalueFxCommand(LocalDate.now(zone)))
                if (result.posted) {
                    log.infof("Daily FX revaluation posted for %s: %s", result.date, result.movements)
                } else {
                    log.infof("Daily FX revaluation for %s: no movement", result.date)
                }
                // Success path only: the catch below is a failed run, and recording it as a
                // success is how a liveness gauge becomes a gauge of the scheduler's heartbeat
                // rather than of the workflow.
                liveness?.recordSuccess()
            } catch (ex: Exception) {
                log.errorf(ex, "Daily FX revaluation failed: %s", ex.message)
            }
        }
        if (ran == null) {
            log.infof("Daily FX revaluation: another pod already holds this tick's lock — skipping")
        }
    }

    private companion object {
        const val JOB_NAME = "ledger.fx-revaluation"

        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "ledger-fx-revaluation"
    }
}
