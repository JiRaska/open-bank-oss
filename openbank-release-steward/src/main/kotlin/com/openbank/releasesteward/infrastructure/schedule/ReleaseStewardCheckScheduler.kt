// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.releasesteward.application.port.incoming.RunReleaseStewardCheckUseCase
import com.openbank.releasesteward.domain.model.RunTrigger
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The periodic sweep ADR-0165 decided and nothing ever built.
 *
 * ADR-0165 states the schedule outright: "Runs on a daily 05:00 UTC sweep plus reactively on every release-please
 * PR merge". Only the reactive half was ever built.
 *
 * `RunTrigger.SCHEDULED` has existed in this agent's domain model since the service was written, and nothing ever
 * emitted it. The agent was trigger-only (`POST /api/v1/release-steward/check/trigger`), so it had checked
 * **nothing** since the day it was deployed.
 *
 * The empty findings table is the trap. It renders as "nothing to act on" — the healthiest-looking
 * possible answer — when it actually means nothing has ever looked. An agent that has never run and
 * an agent that runs and finds nothing are indistinguishable from the outside, and no alert fires
 * on a table that simply stops growing.
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method carries no Vert.x context, so a body of
 * `runBlocking { … }` around a reactive call throws `HR000068` and the job aborts silently — five
 * schedulers in this fleet had never run for exactly that reason (#2148, #2187), and it is now a
 * hard rule (`rules.yaml: scheduled_methods`).
 */
@ApplicationScoped
class ReleaseStewardCheckScheduler {

    @Inject
    lateinit var runCheck: RunReleaseStewardCheckUseCase

    @Inject
    lateinit var temporalConfig: TemporalConfig

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private val log = Logger.getLogger(ReleaseStewardCheckScheduler::class.java)
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
        cron = "{openbank.release-steward.check-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runScheduledCheck() {
        fires.incrementAndGet()
        if (!temporalConfig.enabled()) {
            log.info("Temporal disabled; skipping the scheduled release-steward sweep")
            return
        }
        val workflowId = runCheck.startDetached(RunTrigger.SCHEDULED)
        liveness?.recordSuccess()
        log.infof("Scheduled release-steward sweep dispatched as workflow %s", workflowId)
    }

    private companion object {
        const val WORKFLOW_NAME = "release-steward-check"
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
