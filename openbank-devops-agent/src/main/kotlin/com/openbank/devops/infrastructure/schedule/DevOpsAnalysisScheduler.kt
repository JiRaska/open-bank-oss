// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.schedule

import com.openbank.devops.application.port.incoming.RunDevOpsAnalysisUseCase
import com.openbank.devops.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * The daily analysis sweep ADR-0119 decided and nothing ever built.
 *
 * ADR-0119 shows a "schedule: durable analysis sweep" lane in the agent's own diagram, and `RunTrigger.SCHEDULED` has existed in the domain model since the service was
 * written — but nothing ever emitted it. The agent was trigger-only
 * (`POST /api/v1/devops/analysis/trigger`), so it had analysed **nothing**: measured
 * 2026-08-02, the `openbank-devops` Temporal namespace held 0 schedules and 0 workflow
 * executions, and `findings` was empty.
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
class DevOpsAnalysisScheduler {

    @Inject
    lateinit var runAnalysis: RunDevOpsAnalysisUseCase

    @Inject
    lateinit var temporalConfig: TemporalConfig

    private val log = Logger.getLogger(DevOpsAnalysisScheduler::class.java)
    private val fires = AtomicLong(0)

    /**
     * How many times the cron has actually fired in this pod.
     *
     * Exists so a test can assert the SCHEDULE rather than the work: a test that calls the method
     * directly cannot see whether the cron was ever wired, and would pass against a service with no
     * schedule at all — which is the bug being fixed here.
     */
    val fireCount: Long get() = fires.get()

    @Scheduled(
        cron = "{openbank.devops.analysis-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runScheduledAnalysis() {
        fires.incrementAndGet()
        if (!temporalConfig.enabled()) {
            log.info("Temporal disabled; skipping the scheduled DevOps analysis")
            return
        }
        val workflowId = runAnalysis.startDetached(RunTrigger.SCHEDULED)
        log.infof("Scheduled DevOps analysis dispatched as workflow %s", workflowId)
    }
}
