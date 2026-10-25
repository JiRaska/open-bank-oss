// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationReporter
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.out.InFlightRun
import com.openbank.treasury.application.port.out.TreasuryCloseEvidencePort
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
 * Reports treasury-service's day-close participation to ledger-service (ADR-0342), on the pattern of
 * lending-service's `LendingCloseParticipationScheduler`.
 *
 * Every tick re-offers the last [lookbackDays] completed accounting days. Treasury's posting job, the
 * accrual (`InterestAccrualScheduler`), runs on an interval, so it is due on every day on which it is
 * switched on; switched off, the day is SKIPPED with that reason. A due job with no completed
 * `treasury_close_job_run` row means the day is not reported at all and the ledger keeps it in CUTOFF.
 *
 * Two more holds, both "not reported yet", never a guess:
 *  - **in flight**: a pass for the day marked itself started and has not finished. A marker older
 *    than the DERIVED [TreasuryCloseJobs.staleAfter] is a pass that died, reported REFUSED
 *    `pass started and never completed`, so a crash cannot hold the day unresolvably;
 *  - **journal events pending**: treasury posts its journals synchronously, but the deal events that
 *    announce them (`treasury.deal.settled|matured|reversed.v1`) leave through `treasury_outbox`. The
 *    day waits until none created on it is still PENDING, DISPATCHING or FAILED; DEAD ones make the
 *    day REFUSED.
 *
 * The day's journal count is the `deal_journals` rows posted in it, every journal the ledger
 * acknowledged to treasury that day (accruals, settlements, maturities, reversals).
 *
 * Returns the `Uni` for Quarkus to subscribe: a plain `@Scheduled` method has no Vert.x context
 * (CLAUDE.md). `TreasuryCloseParticipationSchedulerCronIT` drives the real cron.
 */
@ApplicationScoped
class TreasuryCloseParticipationScheduler(
    private val evidence: TreasuryCloseEvidencePort,
    sink: CloseParticipationSink,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "openbank.treasury.accrual.enabled") private val accrualEnabled: Boolean,
    @ConfigProperty(name = "openbank.treasury.accrual.pass-budget") private val passBudget: Duration,
    @ConfigProperty(name = "openbank.treasury.close.lookback-days") private val lookbackDays: Long,
) {
    private val staleAfter: Duration get() = TreasuryCloseJobs.staleAfter(passBudget)

    private val log = Logger.getLogger(TreasuryCloseParticipationScheduler::class.java)
    private val reporter = CloseParticipationReporter(TreasuryCloseJobs.PARTICIPANT, sink, meterRegistry)
    private val accountingClock = AccountingClock.bank(clock)
    private var liveness: WorkflowLivenessRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofHours(1))
    }

    @Scheduled(
        cron = "{openbank.treasury.close.report-cron}",
        identity = "treasury-close-participation",
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
                    "treasury day-close participation: %d of %d report(s) NOT accepted by the ledger - " +
                        "liveness not refreshed",
                    result.attempted - result.delivered,
                    result.attempted,
                )
            }
        }
        .onFailure().invoke { e -> log.errorf(e, "treasury day-close participation tick failed") }
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
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        return evidence.findBetween(from, to).flatMap { runs ->
            evidence.inFlightBetween(from, to).flatMap { inFlight ->
                Multi.createFrom().iterable(days)
                    .onItem().transformToUniAndConcatenate { day -> assemble(day, runs, inFlight) }
                    .collect().asList()
                    .flatMap { assembled ->
                        Multi.createFrom().iterable(assembled.filterNotNull())
                            .onItem().transformToUniAndConcatenate { reporter.report(it) }
                            .collect().asList()
                    }
                    .map { results -> TickResult(attempted = results.size, delivered = results.count { it }) }
            }
        }
    }

    private fun assemble(
        day: LocalDate,
        runs: List<CloseJobRun>,
        inFlight: List<InFlightRun>,
    ): Uni<CloseParticipationReport?> {
        val due = dueJobs()
        if (due.isEmpty()) {
            return Uni.createFrom().item(CloseParticipationAssembler.assemble(day, due, runs, SKIP_REASON))
        }
        val now = clock.instant()
        val flying = inFlight.filter { it.businessDate == day && it.job in due }
        if (flying.any { it.startedAt.plus(staleAfter).isAfter(now) }) {
            log.infof("treasury day-close for %s not reported: a pass for it is still in flight", day)
            return Uni.createFrom().nullItem()
        }
        // A marker past staleAfter is a pass that died: that job is REFUSED, never "still running".
        val abandoned = flying.map {
            CloseJobRun(day, it.job, 0, 0, 1, TreasuryCloseJobs.REASON_PASS_ABANDONED, it.startedAt)
        }
        val forDay = runs.filter { it.businessDate == day }
            .filterNot { run -> abandoned.any { it.job == run.job } } + abandoned
        val accrual = forDay.firstOrNull { it.job == TreasuryCloseJobs.ACCRUAL }
        if (accrual == null) {
            log.infof("treasury day-close for %s not reported: the accrual has not run", day)
            return Uni.createFrom().nullItem()
        }
        return evidence.postingsFor(day).map { postings ->
            if (postings.pending > 0) {
                log.infof(
                    "treasury day-close for %s not reported: %d journal event(s) still in the outbox",
                    day,
                    postings.pending,
                )
                return@map null
            }
            val counted = accrual.copy(journalsPosted = postings.journals)
            val folded = if (postings.dead > 0) {
                val reasons = listOfNotNull(
                    accrual.refusalReason, // already carries its counts
                    "${postings.dead} ${TreasuryCloseJobs.REASON_JOURNAL_EVENTS_DEAD}",
                )
                counted.copy(refused = accrual.refused + postings.dead, refusalReason = reasons.joinToString("; "))
            } else {
                counted
            }
            CloseParticipationAssembler.assemble(day, due, listOf(folded), SKIP_REASON)
        }
    }

    /** The accrual is due every day it is switched on. */
    internal fun dueJobs(): Set<String> = if (accrualEnabled) setOf(TreasuryCloseJobs.ACCRUAL) else emptySet()

    private companion object {
        const val WORKFLOW_NAME = "treasury-close-participation"
        const val SKIP_REASON = "treasury interest accrual is switched off"
    }
}
