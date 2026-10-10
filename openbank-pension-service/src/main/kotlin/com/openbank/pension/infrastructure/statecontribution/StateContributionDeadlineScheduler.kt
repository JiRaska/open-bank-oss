// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.statecontribution

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.pension.application.usecase.IncentiveService
import com.openbank.pension.application.usecase.StateContributionReturnService
import com.openbank.pension.domain.statecontribution.CzStateContributionCalendar
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * The daily CZ state-contribution deadline job (ZDPS §16(2), §18; #12382):
 *
 * 1. files every PENDING claim whose quarter has closed. The S3 monthly run already does this on
 *    the 5th, so this is the retry inside the filing month: a quarter missed on the 5th is still
 *    filed before the deadline at month end;
 * 2. files the monthly return report while any return is DUE (§18(4), due by the 10th); a later
 *    filing is logged as late;
 * 3. publishes the deadline check as gauges, so an alert can fire on a missed deadline:
 *    `openbank_pension_state_contribution_deadline_breaches{kind=…}`.
 *
 * `suspend fun` (rules.yaml: scheduled_methods); `StateContributionDeadlineSchedulerCronIT` drives
 * the real cron. Liveness is registered on [StartupEvent] and recorded only on success.
 */
@ApplicationScoped
class StateContributionDeadlineScheduler(
    private val incentives: IncentiveService,
    private val returns: StateContributionReturnService,
    private val domainMetrics: DomainMetrics,
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(StateContributionDeadlineScheduler::class.java)
    private var liveness: WorkflowLivenessRecorder? = null
    private val pastFiling = AtomicInteger()
    private val pastPayment = AtomicInteger()
    private val returnsOverdue = AtomicInteger()

    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, INTERVAL)
        registry.gauge(METRIC, listOf(io.micrometer.core.instrument.Tag.of("kind", "claim_filing")), pastFiling)
        registry.gauge(METRIC, listOf(io.micrometer.core.instrument.Tag.of("kind", "claim_payment")), pastPayment)
        registry.gauge(METRIC, listOf(io.micrometer.core.instrument.Tag.of("kind", "return_due")), returnsOverdue)
    }

    @Scheduled(
        cron = "\${openbank.pension.state-contribution.cz.deadline-cron:0 30 5 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "pension-state-contribution-deadlines",
    )
    suspend fun daily() {
        runCatching { runOnce(LocalDate.now(clock.withZone(ZoneOffset.UTC))) }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.error("state contribution deadline job failed", it) }
    }

    /** One pass; public so a test can drive it with a chosen date. */
    suspend fun runOnce(today: LocalDate) {
        val (filed, unfiled) = incentives.submitPending()
        if (filed.isNotEmpty()) log.infof("filed %d claim batch(es); unfiled formats %s", filed.size, unfiled)
        val month = YearMonth.from(today)
        returns.fileReturnReport(month)?.let { report ->
            if (today.isAfter(CzStateContributionCalendar.returnReportDeadline(month))) {
                log.warnf("return report %s for %s filed after the 10th (ZDPS §18(4))", report.id, month)
            }
        }
        val d = returns.deadlines(today)
        pastFiling.set(d.claimsPastFilingDeadline)
        pastPayment.set(d.claimsPastExpectedPayment)
        returnsOverdue.set(d.returnsOverdue)
        if (d.claimsPastFilingDeadline + d.claimsPastExpectedPayment + d.returnsOverdue > 0) {
            log.errorf("state contribution deadlines missed: %s", d)
        }
    }

    private companion object {
        const val WORKFLOW = "pension-state-contribution-deadlines"
        const val METRIC = "openbank_pension_state_contribution_deadline_breaches"
        val INTERVAL: Duration = Duration.ofDays(1)
    }
}
