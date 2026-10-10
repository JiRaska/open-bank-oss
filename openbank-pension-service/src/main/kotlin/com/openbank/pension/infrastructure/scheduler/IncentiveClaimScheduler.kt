// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.pension.application.port.out.ContractFundingDirectory
import com.openbank.pension.application.usecase.ContributionService
import com.openbank.pension.application.usecase.IncentiveService
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The monthly state-incentive claim run and the subscription retry sweep (ADR-0334 S3).
 *
 * Both are `suspend fun`: a plain `@Scheduled` method runs on a bare executor thread with no
 * Vert.x context, and a `runBlocking` body around reactive I/O aborts with HR000068 having done
 * nothing (rules.yaml: scheduled_methods, #2148). `IncentiveClaimSchedulerCronIT` drives the REAL
 * cron from a test profile, because a direct call supplies the context the scheduler does not.
 *
 * Liveness (ADR-0237) is registered on [StartupEvent] — `@ApplicationScoped` is lazy — and recorded
 * only on the success branch.
 */
@ApplicationScoped
class IncentiveClaimScheduler(
    private val incentives: IncentiveService,
    private val contributions: ContributionService,
    private val directory: ContractFundingDirectory,
    private val domainMetrics: DomainMetrics,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(IncentiveClaimScheduler::class.java)

    private var claimLiveness: WorkflowLivenessRecorder? = null
    private var sweepLiveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        claimLiveness = domainMetrics.registerWorkflowLiveness(CLAIM_WORKFLOW, CLAIM_INTERVAL)
        sweepLiveness = domainMetrics.registerWorkflowLiveness(SWEEP_WORKFLOW, SWEEP_INTERVAL)
    }

    /** Claims the month that has just closed. Re-running it is harmless (claims are unique per period). */
    @Scheduled(
        cron = "\${openbank.pension.incentive-claim-cron:0 0 4 5 * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "pension-incentive-claims",
    )
    suspend fun monthlyClaims() {
        val period = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1)
        runCatching { incentives.runMonthlyClaims(period) }
            .onSuccess {
                claimLiveness?.recordSuccess()
                log.infof(
                    "incentive claims for %s: %d created, %d batch(es) filed, unfiled formats %s",
                    period,
                    it.claimsCreated,
                    it.batches.size,
                    it.unfiledFormats,
                )
            }
            .onFailure { log.error("monthly incentive claim run failed", it) }
    }

    @Scheduled(
        cron = "\${openbank.pension.subscription-sweep-cron:0 */15 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "pension-subscription-sweep",
    )
    suspend fun subscriptionSweep() {
        runCatching { contributions.placeMissingSubscriptions(directory.fundable().map { it.contractId }) }
            .onSuccess { placed ->
                sweepLiveness?.recordSuccess()
                if (placed > 0) log.infof("placed %d missing subscription order(s)", placed)
            }
            .onFailure { log.error("subscription sweep failed", it) }
    }

    private companion object {
        const val CLAIM_WORKFLOW = "pension-incentive-claims"
        const val SWEEP_WORKFLOW = "pension-subscription-sweep"
        const val CLAIM_INTERVAL_DAYS = 31L
        const val SWEEP_INTERVAL_MINUTES = 15L
        val CLAIM_INTERVAL: Duration = Duration.ofDays(CLAIM_INTERVAL_DAYS)
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(SWEEP_INTERVAL_MINUTES)
    }
}
