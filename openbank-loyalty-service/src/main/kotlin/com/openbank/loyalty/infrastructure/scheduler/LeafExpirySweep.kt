// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.loyalty.application.usecase.ExpireLeavesUseCase
import com.openbank.loyalty.application.usecase.ProvisioningSummaryUseCase
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Runs expiry and the provisioning summary.
 *
 * Both are `suspend fun`. A plain `@Scheduled` method runs on a bare executor thread with no
 * Vert.x context, so a reactive Panache call inside `runBlocking` throws HR000068 and the job
 * aborts silently having done nothing. That is not hypothetical here — five schedulers in this
 * fleet shipped that way and had never once run. A test that calls these methods directly cannot
 * see that class of bug, because the direct call supplies the context the scheduler does not.
 *
 * Both jobs are quiet by nature: an expiry sweep with nothing due and a provisioning summary of an
 * unchanged obligation both do exactly nothing visible, so a permanently broken schedule looks
 * identical to a healthy one from outside. [DomainMetrics.registerWorkflowLiveness] publishes the
 * last-success age (ADR-0237) so the staleness rule and `openbank-control-liveness-sentinel` can
 * tell the two apart. [WorkflowLivenessRecorder.recordSuccess] is called only where the work
 * actually returned, never on the failure branch — a heartbeat on the failure path asserts the very
 * thing it exists to disprove.
 *
 * Registration hangs off [StartupEvent] rather than `@PostConstruct`, because `@ApplicationScoped`
 * is lazy: a `@PostConstruct` here would first run when the cron first fires, leaving the gauge
 * absent until then, and absent is a different signal from stale.
 */
@ApplicationScoped
class LeafExpirySweep(
    private val expire: ExpireLeavesUseCase,
    private val provisioning: ProvisioningSummaryUseCase,
    private val domainMetrics: DomainMetrics,
) {
    private var expiryLiveness: WorkflowLivenessRecorder? = null
    private var provisioningLiveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        expiryLiveness = domainMetrics.registerWorkflowLiveness(EXPIRY_WORKFLOW, EXPECTED_INTERVAL)
        provisioningLiveness = domainMetrics.registerWorkflowLiveness(PROVISIONING_WORKFLOW, EXPECTED_INTERVAL)
    }

    @Scheduled(
        cron = "\${openbank.loyalty.expiry-cron:0 15 2 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "loyalty-leaf-expiry-sweep",
    )
    suspend fun expireLots() {
        runCatching { expire.sweep() }
            .onSuccess { expired ->
                expiryLiveness?.recordSuccess()
                // Logged at info even when zero: "the sweep ran and found nothing" and "the sweep
                // did not run" are different facts, and only the first one leaves a line.
                log.infof("loyalty.expiry.sweep expired_lots=%d", expired)
            }
            .onFailure { log.error("loyalty leaf expiry sweep failed", it) }
    }

    @Scheduled(
        cron = "\${openbank.loyalty.provisioning-cron:0 30 2 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "loyalty-provisioning-summary",
    )
    suspend fun publishProvisioningSummary() {
        runCatching { provisioning.summarise() }
            .onSuccess { summary ->
                provisioningLiveness?.recordSuccess()
                log.infof(
                    "loyalty.provisioning.summary at=%s outstanding_leaves=%d",
                    summary.at,
                    summary.outstandingLeaves,
                )
            }
            .onFailure { log.error("loyalty provisioning summary failed", it) }
    }

    private companion object {
        private val log: Logger = Logger.getLogger(LeafExpirySweep::class.java)

        const val EXPIRY_WORKFLOW = "loyalty-leaf-expiry-sweep"
        const val PROVISIONING_WORKFLOW = "loyalty-provisioning-summary"

        // Both crons are daily by default. An operator who widens either property widens the
        // expected interval with it, so the alert threshold follows the schedule rather than a
        // number written once and forgotten.
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
