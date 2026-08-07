// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.schedule

import com.openbank.finops.application.port.incoming.RunFinOpsAnalysisUseCase
import com.openbank.finops.domain.model.RunTrigger
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.temporal.TemporalConfig
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The daily analysis sweep ADR-0112 decided and nothing ever built.
 *
 * ADR-0112 states "schedule: daily 03:00 UTC" outright, and `RunTrigger.SCHEDULED` has existed in the domain model since the service was
 * written — but nothing ever emitted it. The agent was trigger-only
 * (`POST /api/v1/finops/analysis/trigger`), so it had analysed **nothing**: measured
 * 2026-08-02, the `openbank-finops` Temporal namespace held 0 schedules and 0 workflow
 * executions, and `anomalies` was empty.
 *
 * The empty table is the trap. It renders as "nothing to act on" — the healthiest-looking possible
 * answer — when it actually means nothing has ever looked. An agent that has never run and an agent
 * that runs and finds nothing are indistinguishable from the outside.
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method carries no Vert.x context, so a body of
 * `runBlocking { … }` around a reactive call throws `HR000068` and the job aborts silently —
 * five schedulers in this fleet had never run for exactly that reason (#2148, #2187), and it is now
 * a hard rule (`rules.yaml: scheduled_methods`).
 */
@ApplicationScoped
class FinOpsAnalysisScheduler {

    @Inject
    lateinit var runAnalysis: RunFinOpsAnalysisUseCase

    @Inject
    lateinit var temporalConfig: TemporalConfig

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private val log = Logger.getLogger(FinOpsAnalysisScheduler::class.java)
    private val fires = AtomicLong(0)
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun registerLiveness() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    /**
     * How many times the cron has actually fired in this pod.
     *
     * Exists so a test can assert the SCHEDULE rather than the work: a test that calls the method
     * directly cannot see whether the cron was ever wired, and would pass against a service with no
     * schedule at all — which is the bug being fixed here.
     */
    val fireCount: Long get() = fires.get()

    @Scheduled(
        cron = "{openbank.finops.analysis-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runScheduledAnalysis() {
        fires.incrementAndGet()
        if (!temporalConfig.enabled()) {
            log.info("Temporal disabled; skipping the scheduled FinOps analysis")
            return
        }
        val workflowId = runAnalysis.startDetached(RunTrigger.SCHEDULED)
        liveness?.recordSuccess()
        log.infof("Scheduled FinOps analysis dispatched as workflow %s", workflowId)
    }

    private companion object {
        const val WORKFLOW_NAME = "finops-analysis"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
