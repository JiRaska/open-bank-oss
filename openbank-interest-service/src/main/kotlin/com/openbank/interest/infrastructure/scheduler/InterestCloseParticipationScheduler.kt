// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.openbank.interest.application.port.out.CloseJobRunPort
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
 * Reports interest-service's day-close participation to ledger-service (ADR-0342).
 *
 * Every tick re-offers the last [lookbackDays] completed days. For each, the jobs that were DUE
 * (accrual every day its cron is on; capitalization on the days its cron fires) are folded from
 * `interest_close_job_run` by [CloseParticipationAssembler]: a due job with no run means the day
 * is not reported at all, so the ledger keeps it in CUTOFF and pages — interest running late holds
 * the close instead of the close passing without it. Re-offering is cheap: the reporter does not
 * re-send a report it already delivered unchanged.
 *
 * Returns the `Uni` for Quarkus to subscribe, the shape of the other interest schedulers — a plain
 * `@Scheduled` method has no Vert.x context, so `runBlocking` around reactive Panache would throw
 * `HR000068` and never run (CLAUDE.md).
 */
@ApplicationScoped
class InterestCloseParticipationScheduler(
    private val closeJobRuns: CloseJobRunPort,
    sink: CloseParticipationSink,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.interest.accrual-cron") private val accrualCron: String,
    @ConfigProperty(name = "openbank.interest.capitalization-cron") private val capitalizationCron: String,
    @ConfigProperty(name = "openbank.interest.close.lookback-days", defaultValue = "3")
    private val lookbackDays: Long,
) {
    private val log = Logger.getLogger(InterestCloseParticipationScheduler::class.java)
    private val reporter = CloseParticipationReporter(InterestCloseJobs.PARTICIPANT, sink, meterRegistry)
    private val accountingClock = AccountingClock.bank(clock)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.interest.close.report-cron:0 30 * * * ?}",
        identity = "interest-close-participation",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reportRecentDays(): Uni<Void> = reportDays(accountingClock.today())
        // Liveness means "reports reach the ledger", not "the tick ran": a ledger that is
        // unreachable, 403 or 404 for good would otherwise leave this gauge fresh forever while no
        // day is ever delivered. A tick that attempted nothing (every day still waiting on a job)
        // is healthy - it has nothing to deliver - and so is one where every attempt was accepted.
        .onItem().invoke { result ->
            if (result.allDelivered) {
                liveness?.recordSuccess()
            } else {
                log.warnf(
                    "interest day-close participation: %d of %d report(s) NOT accepted by the ledger - " +
                        "liveness not refreshed",
                    result.attempted - result.delivered,
                    result.attempted,
                )
            }
        }
        .onFailure().invoke { e -> log.errorf(e, "interest day-close participation tick failed") }
        .onFailure().recoverWithNull()
        .replaceWithVoid()

    /** How many reports a tick tried to send and how many the ledger accepted. */
    data class TickResult(val attempted: Int, val delivered: Int) {
        val allDelivered: Boolean get() = delivered == attempted
    }

    /** Report every completed day in `[today - lookbackDays, today - 1]`. Visible for the IT. */
    fun reportDays(today: LocalDate): Uni<TickResult> {
        val from = today.minusDays(lookbackDays)
        val to = today.minusDays(1)
        return closeJobRuns.findBetween(from, to).flatMap { runs ->
            val reports = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }
                .mapNotNull { day ->
                    CloseParticipationAssembler.assemble(day, dueJobs(day), runs, skipReason = SKIP_REASON).also {
                        if (it == null) log.infof("interest day-close for %s not reported: a due job has not run", day)
                    }
                }
                .toList()
            Multi.createFrom().iterable(reports)
                .onItem().transformToUniAndConcatenate { reporter.report(it) }
                .collect().asList()
                .map { results -> TickResult(attempted = results.size, delivered = results.count { it }) }
        }
    }

    internal fun dueJobs(day: LocalDate): Set<String> = buildSet {
        if (firesOn(accrualCron, day)) add(InterestCloseJobs.ACCRUAL)
        if (firesOn(capitalizationCron, day)) add(InterestCloseJobs.CAPITALIZATION)
    }

    /** True when [cron] (Quartz syntax, as Quarkus reads it) fires at least once on [day]. */
    private fun firesOn(cron: String, day: LocalDate): Boolean {
        if (cron.isBlank() || cron.trim().equals(OFF, ignoreCase = true)) return false
        val start = day.atStartOfDay(accountingClock.zone)
        val next = ExecutionTime.forCron(PARSER.parse(cron)).nextExecution(start.minusNanos(1))
        return next.map { it.toLocalDate() == day }.orElse(false)
    }

    private companion object {
        const val WORKFLOW_NAME = "interest-close-participation"
        const val OFF = "off"
        const val SKIP_REASON = "no interest job is scheduled on this day (accrual and capitalization crons off)"
        val PARSER = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
    }
}
