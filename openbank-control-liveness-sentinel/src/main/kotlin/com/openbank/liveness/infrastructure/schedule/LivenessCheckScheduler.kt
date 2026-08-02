// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.schedule

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.liveness.application.port.incoming.RunLivenessCheckUseCase
import com.openbank.liveness.domain.model.RunTrigger
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.util.concurrent.atomic.AtomicLong

/**
 * The daily schedule ADR-0163 decided and nothing ever built.
 *
 * ADR-0163 says the sentinel "runs daily (03:00 UTC, offset from finops-agent/devops-agent) plus
 * reactively", and `RunTrigger.SCHEDULED` has existed in the domain model since the service was
 * written -- but nothing ever emitted it. The service was trigger-only
 * (`POST /api/v1/liveness-sentinel/check/trigger`), so ADR-0160 mechanism 3 had evaluated
 * **nothing**: measured 2026-08-02, the `openbank-liveness` Temporal namespace held 0 schedules and
 * 0 workflow executions, and the `findings` table was empty.
 *
 * An empty findings table is the problem. It renders in the admin UI and reads as "no stale
 * controls" -- the healthiest possible answer -- when it actually means "nothing has ever looked".
 * A control that has never run and a control that runs and finds nothing are indistinguishable
 * from the outside, which is precisely the failure class this agent exists to detect in OTHER
 * services.
 *
 * Written as a `suspend fun` on purpose. A plain `@Scheduled` method carries no Vert.x context, so
 * a body of `runBlocking { ... }` around a reactive call throws `HR000068` and the job aborts
 * having done nothing, silently -- five schedulers in this fleet had never run for exactly that
 * reason (#2148, #2187), and it is now a hard rule (`rules.yaml: scheduled_methods`).
 */
@ApplicationScoped
class LivenessCheckScheduler {

    @Inject
    lateinit var runCheck: RunLivenessCheckUseCase

    @Inject
    lateinit var temporalConfig: TemporalConfig

    private val log = Logger.getLogger(LivenessCheckScheduler::class.java)
    private val fires = AtomicLong(0)

    /**
     * How many times the cron has actually fired in this pod.
     *
     * Exists so a test can assert the SCHEDULE, not the work: a test that calls the method
     * directly cannot see whether the cron was ever wired, and would pass against a service with
     * no schedule at all -- which is the exact bug being fixed here.
     */
    val fireCount: Long get() = fires.get()

    @Scheduled(
        cron = "{openbank.liveness-sentinel.check-cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runScheduledCheck() {
        fires.incrementAndGet()
        if (!temporalConfig.enabled()) {
            log.info("Temporal disabled; skipping the scheduled control-liveness check")
            return
        }
        val workflowId = runCheck.startDetached(RunTrigger.SCHEDULED)
        log.infof("Scheduled control-liveness check dispatched as workflow %s", workflowId)
    }
}
