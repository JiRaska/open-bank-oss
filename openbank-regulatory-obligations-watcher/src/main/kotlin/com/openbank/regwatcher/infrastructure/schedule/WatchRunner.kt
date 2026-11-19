// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.regwatcher.application.WatchRunService
import com.openbank.regwatcher.domain.WatchRunResult
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import io.quarkus.vertx.ConsumeEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jboss.logging.Logger
import java.time.Duration

/**
 * The weekly sweep (charter `schedule.weekly: Monday 06:30 UTC`; the cron is set explicitly in the
 * gitops Deployment and held equal to the charter by `check-agent-schedule-matches-charter.py`).
 *
 * `suspend fun` on purpose: a plain `@Scheduled` method carries no Vert.x context, and the reactive
 * state store would abort the job silently (the scheduled_methods governance rule). `@Startup` so the
 * liveness gauge exists from boot rather than from the first (weekly) run.
 *
 * Liveness (ADR-0237) is recorded ONLY when the run read at least one source. A run in which every
 * source came back DENIED / NOT_RENDERABLE (broker down, credential missing, policy denying) did
 * not watch anything, and reporting it as a success would make a blind watcher indistinguishable
 * from a quiet one — the exact failure the gauge exists to expose.
 */
@Startup
@ApplicationScoped
class WatchRunner {
    @Inject
    lateinit var service: WatchRunService

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @Inject
    lateinit var registry: MeterRegistry

    private val log = Logger.getLogger(WatchRunner::class.java)
    private val mutex = Mutex()
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun registerLiveness() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "{openbank.regulatory-watcher.check-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun scheduled() {
        runOnce("scheduled")
    }

    /** One run, serialised. Also the path the operator trigger uses. */
    suspend fun runOnce(trigger: String): WatchRunResult = mutex.withLock {
        val result = service.run()
        record(result)
        log.infof(
            "regulatory-watcher run (%s): attempted=%d fetched=%d unverified=%d changed=%d filed=%d deferred=%d tokens=%d skipped=%s",
            trigger, result.sourcesAttempted, result.fetched, result.unverified, result.changed,
            result.issuesFiled, result.deferred, result.tokensUsed, result.skippedReason ?: "-",
        )
        result
    }

    @Suppress("UNUSED_PARAMETER")
    @ConsumeEvent("llm-gateway.kill")
    fun onKill(event: String) {
        service.killed = true
        log.warn("kill switch received — regulatory-obligations-watcher will not run until restarted")
    }

    private fun record(r: WatchRunResult) {
        val outcome = when {
            r.skippedReason != null -> "skipped"
            r.livenessEarned -> "success"
            else -> "blind"
        }
        registry.counter("openbank_regulatory_watcher_runs_total", "outcome", outcome).increment()
        registry.counter(
            "openbank_regulatory_watcher_sources_total",
            "outcome",
            "fetched",
        ).increment(r.fetched.toDouble())
        registry.counter(
            "openbank_regulatory_watcher_sources_total",
            "outcome",
            "unverified",
        ).increment(r.unverified.toDouble())
        registry.counter("openbank_regulatory_watcher_sources_changed_total").increment(r.changed.toDouble())
        registry.counter("openbank_regulatory_watcher_issues_filed_total").increment(r.issuesFiled.toDouble())
        registry.counter("openbank_regulatory_watcher_findings_deferred_total").increment(r.deferred.toDouble())
        registry.counter("openbank_regulatory_watcher_llm_tokens_total").increment(r.tokensUsed.toDouble())
        if (r.livenessEarned) liveness?.recordSuccess()
    }

    companion object {
        const val WORKFLOW = "regulatory-obligations-watch"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(7)
    }
}
