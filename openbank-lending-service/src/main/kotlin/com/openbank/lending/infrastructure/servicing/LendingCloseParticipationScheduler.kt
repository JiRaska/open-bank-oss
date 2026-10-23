// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReporter
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Reports lending-service's day-close participation to ledger-service (ADR-0342). The same shape
 * as interest-service's `InterestCloseParticipationScheduler`, which is the reference.
 *
 * Every tick re-offers the last [lookbackDays] completed accounting days. Lending's two posting
 * jobs ([InterestAccrualScheduler], [ProvisioningCycleScheduler]) run on an INTERVAL, not a cron,
 * so both are due on every day on which they are switched on. Their `lending_close_job_run` rows
 * are folded by [CloseParticipationAssembler]: a due job with no row for the day means the day is
 * not reported at all and the ledger keeps it in CUTOFF — lending running late holds the close.
 * Both switched off (`off`/`disabled`) is reported SKIPPED with that reason.
 *
 * Returns the `Uni` for Quarkus to subscribe: a plain `@Scheduled` method has no Vert.x context,
 * so `runBlocking` around reactive Panache would throw `HR000068` and never run (CLAUDE.md).
 * `LendingCloseParticipationSchedulerCronIT` drives the real cron.
 */
@ApplicationScoped
class LendingCloseParticipationScheduler(
    private val closeJobRuns: CloseJobRunPort,
    sink: CloseParticipationSink,
    meterRegistry: MeterRegistry,
    clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "lending.servicing.accrual.every") private val accrualEvery: String,
    @ConfigProperty(name = "lending.provisioning.cycle.every") private val provisioningEvery: String,
    @ConfigProperty(name = "lending.close.lookback-days", defaultValue = "3")
    private val lookbackDays: Long,
) {
    private val log = Logger.getLogger(LendingCloseParticipationScheduler::class.java)
    private val reporter = CloseParticipationReporter(LendingCloseJobs.PARTICIPANT, sink, meterRegistry)
    private val accountingClock = AccountingClock.bank(clock)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{lending.close.report-cron:0 40 * * * ?}",
        identity = "lending-close-participation",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reportRecentDays(): Uni<Void> = reportDays(accountingClock.today())
        // Liveness means "reports reach the ledger", not "the tick ran" (same rule as interest).
        .onItem().invoke { result ->
            if (result.allDelivered) {
                liveness?.recordSuccess()
            } else {
                log.warnf(
                    "lending day-close participation: %d of %d report(s) NOT accepted by the ledger - " +
                        "liveness not refreshed",
                    result.attempted - result.delivered,
                    result.attempted,
                )
            }
        }
        .onFailure().invoke { e -> log.errorf(e, "lending day-close participation tick failed") }
        .onFailure().recoverWithNull()
        .replaceWithVoid()

    /** How many reports a tick tried to send and how many the ledger accepted. */
    data class TickResult(val attempted: Int, val delivered: Int) {
        val allDelivered: Boolean get() = delivered == attempted
    }

    /** Report every completed day in `[today - lookbackDays, today - 1]`. Visible for tests. */
    fun reportDays(today: LocalDate): Uni<TickResult> {
        val from = today.minusDays(lookbackDays)
        val to = today.minusDays(1)
        val due = dueJobs()
        return closeJobRuns.findBetween(from, to).flatMap { runs ->
            val reports = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }
                .mapNotNull { day ->
                    CloseParticipationAssembler.assemble(day, due, runs, skipReason = SKIP_REASON).also {
                        if (it == null) log.infof("lending day-close for %s not reported: a due job has not run", day)
                    }
                }
                .toList()
            Multi.createFrom().iterable(reports)
                .onItem().transformToUniAndConcatenate { reporter.report(it) }
                .collect().asList()
                .map { results -> TickResult(attempted = results.size, delivered = results.count { it }) }
        }
    }

    /** Interval jobs are due every day they are switched on. */
    internal fun dueJobs(): Set<String> = buildSet {
        if (isOn(accrualEvery)) add(LendingCloseJobs.ACCRUAL)
        if (isOn(provisioningEvery)) add(LendingCloseJobs.PROVISIONING)
    }

    private fun isOn(every: String): Boolean = every.isNotBlank() && every.trim().lowercase() !in OFF_VALUES

    private companion object {
        const val WORKFLOW_NAME = "lending-close-participation"

        /** The values Quarkus reads as "never run" for `every`. */
        val OFF_VALUES = setOf("off", "disabled")
        const val SKIP_REASON = "no lending job is scheduled (accrual and provisioning intervals off)"
    }
}
