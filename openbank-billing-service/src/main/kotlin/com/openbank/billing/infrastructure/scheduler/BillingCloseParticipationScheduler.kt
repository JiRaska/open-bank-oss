// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.openbank.billing.application.port.out.BillingCloseEvidencePort
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReport
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
 * Reports billing-service's day-close participation to ledger-service (ADR-0342), on the pattern of
 * interest-service's `InterestCloseParticipationScheduler`.
 *
 * Every tick revisits the current and previous calendar months, or a longer [lookbackDays] window.
 * The cycle is DUE on a day
 * when the sweep is enabled and its cron fires on that day (by default 03:00 on the 1st); every
 * other gap-free day is SKIPPED with that reason. A due day is RAN only when BOTH hold:
 *
 *  1. the sweep recorded its `billing_close_job_run` row for the day — otherwise billing has not
 *     finished and the day is REFUSED, revoking an earlier positive report;
 *  2. no fee of that cycle is still `PENDING` in the outbox — the journals are booked by the outbox
 *     after the sweep, and reporting before they land would claim journals that do not exist yet.
 *
 * Pending or failed fees refuse every reported day of their monthly cycle, regardless of current
 * scheduler flags. A recorded historical run remains its cycle's owner even after the sweep stops.
 * A finished day is RAN with the booked journals, or
 * REFUSED when accounts failed to assess, the sweep
 * aborted, or fees ended `FAILED` (the ledger refused the day with `ACCOUNTING_DAY_CLOSED`, or the
 * outbox exhausted its retries).
 *
 * Returns the `Uni` for Quarkus to subscribe: a plain `@Scheduled` method has no Vert.x context
 * (CLAUDE.md). `BillingCloseParticipationSchedulerCronIT` drives the real cron.
 */
@ApplicationScoped
class BillingCloseParticipationScheduler(
    private val evidence: BillingCloseEvidencePort,
    sink: CloseParticipationSink,
    meterRegistry: MeterRegistry,
    clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.billing.scheduler.enabled", defaultValue = "false")
    private val cycleEnabled: Boolean,
    @ConfigProperty(name = "openbank.billing.scheduler.cron") private val cycleCron: String,
    @ConfigProperty(name = "openbank.billing.close.lookback-days", defaultValue = "3") private val lookbackDays: Long,
) {

    private val log = Logger.getLogger(BillingCloseParticipationScheduler::class.java)
    private val reporter = CloseParticipationReporter(BillingCloseJobs.PARTICIPANT, sink, meterRegistry)
    private val accountingClock = AccountingClock.bank(clock)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.billing.close.report-cron:0 50 * * * ?}",
        identity = "billing-close-participation",
        timeZone = BillingCloseJobs.SCHEDULE_ZONE,
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reportRecentDays(): Uni<Void> = reportDays(accountingClock.today())
        // Liveness means "reports reach the ledger", not "the tick ran" (same rule as interest).
        .onItem().invoke { result ->
            if (result.allDelivered) {
                liveness?.recordSuccess()
            } else {
                log.warnf(
                    "billing day-close participation: %d of %d report(s) NOT accepted by the ledger - " +
                        "liveness not refreshed",
                    result.attempted - result.delivered,
                    result.attempted,
                )
            }
        }
        .onFailure().invoke { e -> log.errorf(e, "billing day-close participation tick failed") }
        .onFailure().recoverWithNull()
        .replaceWithVoid()

    /** How many reports a tick tried to send and how many the ledger accepted. */
    data class TickResult(val attempted: Int, val delivered: Int) {
        val allDelivered: Boolean get() = delivered == attempted
    }

    /** Revisit every completed day in the monthly evidence and configured recovery windows. */
    fun reportDays(today: LocalDate): Uni<TickResult> {
        val from = CloseParticipationAssembler.reportingFrom(today, lookbackDays)
        val to = today.minusDays(1)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        return evidence.findBetween(from, to).flatMap { runs ->
            Multi.createFrom().iterable(days)
                .onItem().transformToUniAndConcatenate { day -> assemble(day, runs) }
                .collect().asList()
                .flatMap { assembled ->
                    Multi.createFrom().iterable(assembled.filterNotNull())
                        .onItem().transformToUniAndConcatenate { reporter.report(it) }
                        .collect().asList()
                }
                .map { results -> TickResult(attempted = results.size, delivered = results.count { it }) }
        }
    }

    private fun assemble(day: LocalDate, runs: List<CloseJobRun>): Uni<CloseParticipationReport?> {
        val cycle = BillingCycleScheduler.cycleIdFor(day)
        val run = runs.firstOrNull { it.businessDate == day && it.job == BillingCloseJobs.CYCLE }
        return evidence.postingsFor(cycle).map { postings ->
            // Disabling or moving a sweep cannot cancel fees already queued for its monthly cycle.
            // Their original run day may already be closed, so unresolved fees hold other days too.
            if (postings.pending > 0) {
                log.infof(
                    "billing day-close for %s refused: %d fee posting(s) still pending",
                    day,
                    postings.pending,
                )
                return@map CloseParticipationAssembler.incomplete(
                    day,
                    PENDING_FEES,
                    listOfNotNull(run?.toString(), postings.toString()),
                )
            }
            if (run == null && postings.failed > 0) {
                return@map CloseParticipationAssembler.incomplete(day, FAILED_FEES, listOf(postings.toString()))
            }
            if (run == null) {
                val cycleRecorded = runs.any {
                    it.job == BillingCloseJobs.CYCLE && BillingCycleScheduler.cycleIdFor(it.businessDate) == cycle
                }
                if (!isDue(day) && (cycleRecorded || postings.posted == 0)) {
                    return@map CloseParticipationAssembler.assemble(
                        day,
                        emptySet(),
                        runs,
                        skipReason = "${skipReason()}; ${postings.posted} completed fee posting(s) for cycle $cycle",
                    )
                }
                return@map CloseParticipationAssembler.incomplete(
                    day,
                    if (isDue(day)) INCOMPLETE_CYCLE else UNOWNED_FEES,
                    listOf(BillingCloseJobs.CYCLE, postings.toString()),
                )
            }
            val refused = run.refused + postings.failed
            val reason = listOfNotNull(
                run.refusalReason?.let { "${run.refused} $it" },
                if (postings.failed > 0) "${postings.failed} ${BillingCloseJobs.REASON_FEES_NOT_POSTED}" else null,
            ).joinToString("; ").ifBlank { null }
            val folded = run.copy(journalsPosted = postings.posted, refused = refused, refusalReason = reason)
            CloseParticipationAssembler.assemble(day, setOf(BillingCloseJobs.CYCLE), listOf(folded))
        }
    }

    /** True when the enabled cycle's cron (Quartz syntax, as Quarkus reads it) fires on [day]. */
    internal fun isDue(day: LocalDate): Boolean {
        if (!cycleEnabled || cycleCron.isBlank() || cycleCron.trim().lowercase() in OFF_VALUES) return false
        val start = day.atStartOfDay(accountingClock.zone)
        val next = ExecutionTime.forCron(PARSER.parse(cycleCron)).nextExecution(start.minusNanos(1))
        return next.map { it.toLocalDate() == day }.orElse(false)
    }

    private fun skipReason() = if (cycleEnabled) SKIP_NOT_DUE else SKIP_DISABLED

    private companion object {
        const val WORKFLOW_NAME = "billing-close-participation"
        val OFF_VALUES = setOf("off", "disabled")
        const val SKIP_NOT_DUE = "no billing cycle is scheduled on this day"
        const val SKIP_DISABLED = "billing cycle sweep is switched off"
        const val INCOMPLETE_CYCLE = "BILLING_CYCLE_INCOMPLETE"
        const val PENDING_FEES = "BILLING_FEES_PENDING"
        const val FAILED_FEES = "BILLING_FEES_FAILED"
        const val UNOWNED_FEES = "BILLING_FEES_WITHOUT_COMPLETED_CYCLE"
        val PARSER = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
    }
}
