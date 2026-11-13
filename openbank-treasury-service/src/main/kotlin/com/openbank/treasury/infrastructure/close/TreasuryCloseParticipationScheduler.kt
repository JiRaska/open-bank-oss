// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.close.CloseOutcome
import com.openbank.libs.close.CloseParticipationAssembler
import com.openbank.libs.close.CloseParticipationReport
import com.openbank.libs.close.CloseParticipationReporter
import com.openbank.libs.close.CloseParticipationSink
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.out.DayJournalPostings
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
 * Every tick refreshes the configured recovery window and the whole readiness month. Treasury's posting job, the
 * accrual (`InterestAccrualScheduler`), runs on an interval, so it is due on every day on which it is
 * switched on; switched off, the day is SKIPPED with that reason. A due job with no completed
 * `treasury_close_job_run` row explicitly refuses the day, revoking any earlier completion.
 *
 * Additional incomplete-work receipts explicitly refuse completion:
 *  - **in flight**: a pass for the day marked itself started and has not finished. A marker older
 *    than the DERIVED [TreasuryCloseJobs.staleAfter] is a pass that died, reported REFUSED
 *    `pass started and never completed`, so a crash cannot hold the day unresolvably;
 *  - **journal events pending**: treasury posts its journals synchronously, but the deal events that
 *    announce them (`treasury.deal.settled|matured|reversed.v1`) leave through `treasury_outbox`. The
 *    day waits until none associated with its journal value day is still PENDING, DISPATCHING or
 *    FAILED; DEAD ones make the day REFUSED. Unattributable events hold their creation day;
 *  - **unapplied acknowledgement**: a lifecycle journal has no matching committed deal transition.
 *    A completed accrual pass does not prove that the lifecycle state and its event committed;
 *  - **open posting intent** (#13883): settle, mature or reverse decided to post a journal valued on
 *    the day and has not applied it. The ledger may already hold that journal while treasury has not
 *    even recorded it, so the day's count would be short; `PostingIntentReconcileScheduler` re-runs
 *    the command under the same key and completes it.
 *
 * The day's journal count is the `deal_journals` rows with that economic value day: accruals,
 * settlements, maturities and reversals acknowledged by the ledger.
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

    /** Refresh completed days across the configured recovery window and the readiness month. Visible for tests. */
    fun reportDays(today: LocalDate): Uni<TickResult> {
        val from = CloseParticipationAssembler.reportingFrom(today, lookbackDays)
        val to = today.minusDays(1)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        return evidence.requiredJobs(from, to, days.associateWith { dueJobs() }).flatMap { required ->
            evidence.findBetween(from, to).flatMap { runs ->
                evidence.inFlightBetween(from, to).flatMap { inFlight ->
                    Multi.createFrom().iterable(days)
                        .onItem().transformToUniAndConcatenate { day ->
                            assemble(day, runs, inFlight, required[day].orEmpty())
                        }
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
    }

    private fun assemble(
        day: LocalDate,
        runs: List<CloseJobRun>,
        inFlight: List<InFlightRun>,
        required: Set<String>,
    ): Uni<CloseParticipationReport?> = evidence.postingsFor(day).flatMap { postings ->
        // The outbox hold and the DEAD check apply WHATEVER is due (#13882 review): settlements,
        // maturities and reversals post journals with the accrual switched off too.
        lifecycleGap(day, postings)?.let { return@flatMap Uni.createFrom().item(it) }
        val due = required + runs.filter { it.businessDate == day }.map { it.job } +
            inFlight.filter { it.businessDate == day }.map { it.job }
        if (due.isEmpty()) return@flatMap Uni.createFrom().item(withoutDueJob(day, postings))
        val now = clock.instant()
        val flying = inFlight.filter { it.businessDate == day && it.job in due }
        if (flying.any { it.startedAt.plus(staleAfter).isAfter(now) }) {
            log.infof("treasury day-close for %s refused: a pass for it is still in flight", day)
            return@flatMap Uni.createFrom().item(
                CloseParticipationAssembler.incomplete(
                    day,
                    "Treasury accrual is still running",
                    flying.map {
                        it.toString()
                    },
                ),
            )
        }
        // A marker past staleAfter is a pass that died: that job is REFUSED, never "still running".
        val abandoned = flying.map {
            CloseJobRun(day, it.job, 0, 0, 1, TreasuryCloseJobs.REASON_PASS_ABANDONED, it.startedAt)
        }
        val forDay = runs.filter { it.businessDate == day }
            .filterNot { run -> abandoned.any { it.job == run.job } } + abandoned
        val accrual = forDay.firstOrNull { it.job == TreasuryCloseJobs.ACCRUAL }
        if (accrual == null) {
            log.infof("treasury day-close for %s refused: the accrual has not run", day)
            return@flatMap Uni.createFrom().item(
                CloseParticipationAssembler.incomplete(
                    day,
                    "Treasury accrual has not run",
                    due.sorted() + forDay.map { it.toString() },
                ),
            )
        }
        assessedSince(day, postings).map { assessed ->
            if (!assessed) {
                log.infof("treasury day-close for %s refused: a deal settled after the last accrual pass", day)
                return@map CloseParticipationAssembler.incomplete(
                    day,
                    "Treasury settlement has not been assessed",
                    listOf(accrual.toString(), postings.toString()),
                )
            }
            CloseParticipationAssembler.assemble(day, due, listOf(fold(accrual, postings)), SKIP_REASON)
        }
    }

    private fun lifecycleGap(day: LocalDate, postings: DayJournalPostings): CloseParticipationReport? {
        val reason = when {
            postings.pending > 0 -> "Treasury journal delivery is pending"
            postings.openIntents > 0 -> "Treasury posting intents are open"
            postings.unapplied > 0 -> "Treasury journal lifecycle application is pending"
            else -> return null
        }
        log.infof("treasury day-close for %s refused: %s", day, reason)
        return CloseParticipationAssembler.incomplete(day, reason, listOf(postings.toString()))
    }

    /**
     * True when an accrual pass that COMPLETED started after the day's last settlement or maturity —
     * filed under the day itself or any later day (a pass after midnight accrues the day too). A deal
     * settled after the last pass has not been assessed, so its day is held until one has.
     */
    private fun assessedSince(day: LocalDate, postings: DayJournalPostings): Uni<Boolean> {
        val settledAt = postings.lastSettlementAt ?: return Uni.createFrom().item(true)
        return evidence.latestCompletedPassStart(TreasuryCloseJobs.ACCRUAL, day)
            .map { started -> started != null && started.isAfter(settledAt) }
    }

    private fun fold(accrual: CloseJobRun, postings: DayJournalPostings): CloseJobRun {
        val counted = accrual.copy(journalsPosted = postings.journals)
        if (postings.dead == 0) return counted
        val reasons = listOfNotNull(
            accrual.refusalReason, // already carries its counts
            "${postings.dead} ${TreasuryCloseJobs.REASON_JOURNAL_EVENTS_DEAD}",
        )
        return counted.copy(refused = accrual.refused + postings.dead, refusalReason = reasons.joinToString("; "))
    }

    /**
     * No job due (accrual off): the day still carries whatever settlements, maturities and reversals
     * posted. SKIPPED only when it has no journal and no journal event; DEAD events are REFUSED and
     * journals are RAN, never a SKIPPED that would hide them.
     */
    private fun withoutDueJob(day: LocalDate, postings: DayJournalPostings): CloseParticipationReport {
        val report = when {
            postings.dead > 0 -> CloseParticipationReport(
                businessDate = day,
                outcome = CloseOutcome.REFUSED,
                reason = "${postings.dead} ${TreasuryCloseJobs.REASON_JOURNAL_EVENTS_DEAD}",
            )
            postings.journals > 0 -> CloseParticipationReport(
                businessDate = day,
                outcome = CloseOutcome.RAN,
                journalsPosted = postings.journals,
            )
            else -> CloseParticipationReport(businessDate = day, outcome = CloseOutcome.SKIPPED, reason = SKIP_REASON)
        }
        val identity = "$report|$postings"
        return report.copy(
            sourceRunId = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) },
        )
    }

    /** The accrual is due every day it is switched on. */
    internal fun dueJobs(): Set<String> = if (accrualEnabled) setOf(TreasuryCloseJobs.ACCRUAL) else emptySet()

    private companion object {
        const val WORKFLOW_NAME = "treasury-close-participation"
        const val SKIP_REASON = "treasury interest accrual is switched off"
    }
}
