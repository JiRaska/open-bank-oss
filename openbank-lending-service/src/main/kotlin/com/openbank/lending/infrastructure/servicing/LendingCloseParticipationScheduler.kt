// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.out.InFlightRun
import com.openbank.lending.application.port.out.LendingCloseEvidencePort
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
 * Reports lending-service's day-close participation to ledger-service (ADR-0342). The same shape
 * as interest-service's `InterestCloseParticipationScheduler`, which is the reference.
 *
 * Every tick refreshes the configured recovery window and the whole readiness month. Lending's two posting
 * jobs ([InterestAccrualScheduler], [ProvisioningCycleScheduler]) run on an INTERVAL, not a cron,
 * so both are due on every day on which they are switched on. Their `lending_close_job_run` rows
 * are folded by [CloseParticipationAssembler]: a due job with no row for the day means the day is
 * explicitly REFUSED, revoking any earlier completion and keeping the close held.
 * Both switched off (`off`/`disabled`) is reported SKIPPED with that reason.
 *
 * Other incomplete work also emits a stable REFUSED receipt:
 *  - **in flight** — a pass for the day has marked itself started and not finished, so an earlier
 *    RAN row may be about to change (a pass running across midnight). A marker older than
 *    [LendingCloseJobs.staleAfter] is a pass that died: that job is reported REFUSED `pass started and never completed`,
 *    so a crash cannot hold the day unresolvably;
 *  - **allowance journals pending** — provisioning queues its allowance journals in the outbox, so the
 *    day waits until every `lending.allowance.posting` row for it has been delivered. Durable delivery receipts count as
 *    provisioning's journals after ordinary SENT retention; DEAD rows (the outbox gave up, e.g. on the ledger's closed-day 409) make
 *    the day REFUSED.
 *
 * A report the ledger receives for a day it has already TIED_OUT or LOCKED is stored with `late = true`
 * and never moves the day (ledger `CloseParticipationService`, ADR-0342 D4): a REFUSED that arrives
 * after the close is visible on the day-end page but cannot reopen it.
 *
 * Returns the `Uni` for Quarkus to subscribe: a plain `@Scheduled` method has no Vert.x context,
 * so `runBlocking` around reactive Panache would throw `HR000068` and never run (CLAUDE.md).
 * `LendingCloseParticipationSchedulerCronIT` drives the real cron.
 */
@ApplicationScoped
class LendingCloseParticipationScheduler(
    private val evidence: LendingCloseEvidencePort,
    sink: CloseParticipationSink,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    @ConfigProperty(name = "lending.servicing.accrual.every") private val accrualEvery: String,
    @ConfigProperty(name = "lending.provisioning.cycle.every") private val provisioningEvery: String,
    @ConfigProperty(name = "lending.close.lookback-days", defaultValue = "3") private val lookbackDays: Long,
) {
    /**
     * The accrual's drain budget, from which the stale threshold is DERIVED
     * ([LendingCloseJobs.staleAfter]) rather than configured separately. Field-injected `lateinit`
     * rather than a 9th constructor parameter (detekt LongParameterList fires AT 9) and without a
     * Kotlin default (gate `configproperty-kotlin-defaults`).
     */
    @ConfigProperty(name = "lending.servicing.accrual.drain-budget", defaultValue = "PT10M")
    lateinit var drainBudget: Duration

    private val staleAfter: Duration get() = LendingCloseJobs.staleAfter(drainBudget, lookbackDays)

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

    /** Refresh completed days across the recovery window and the readiness month. Visible for tests. */
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
    ): Uni<CloseParticipationReport?> {
        val due = required + runs.filter { it.businessDate == day }.map { it.job } +
            inFlight.filter { it.businessDate == day }.map { it.job }
        val now = clock.instant()
        val flying = inFlight.filter { it.businessDate == day && it.job in due }
        if (flying.any { it.startedAt.plus(staleAfter).isAfter(now) }) {
            log.infof("lending day-close for %s refused: a pass for it is still in flight", day)
            return Uni.createFrom().item(
                CloseParticipationAssembler.incomplete(
                    day,
                    "Lending pass is still running",
                    flying.map {
                        it.toString()
                    },
                ),
            )
        }
        // A marker past staleAfter is a pass that died: that job is REFUSED, never "still running".
        val abandoned = flying.map {
            CloseJobRun(day, it.job, 0, 0, 1, LendingCloseJobs.REASON_PASS_ABANDONED, it.startedAt)
        }
        val forDay = runs.filter { it.businessDate == day }
            .filterNot { run -> abandoned.any { it.job == run.job } } + abandoned
        return evidence.postingsFor(day).map { postings ->
            allowanceGap(day, postings, forDay)?.let { return@map it }
            val provisioning = forDay.firstOrNull { it.job == LendingCloseJobs.PROVISIONING }
                ?: return@map assembleOrLog(day, due, forDay)
            val folded = if (postings.dead > 0) {
                val reasons = listOfNotNull(
                    provisioning.refusalReason?.let { "${provisioning.refused} $it" },
                    "${postings.dead} ${LendingCloseJobs.REASON_ALLOWANCE_NOT_POSTED}",
                )
                provisioning.copy(
                    journalsPosted = postings.posted,
                    refused = provisioning.refused + postings.dead,
                    refusalReason = reasons.joinToString("; "),
                )
            } else {
                provisioning.copy(journalsPosted = postings.posted)
            }
            assembleOrLog(day, due, forDay.map { if (it.job == LendingCloseJobs.PROVISIONING) folded else it })
        }
    }

    private fun allowanceGap(
        day: LocalDate,
        postings: com.openbank.lending.application.port.out.AllowancePostings,
        runs: List<CloseJobRun>,
    ): CloseParticipationReport? {
        val reason = when {
            !postings.covered -> "ALLOWANCE_RECEIPTS_NOT_COVERED"
            postings.pending > 0 -> "Lending allowance delivery is pending"
            runs.none { it.job == LendingCloseJobs.PROVISIONING } && (postings.dead > 0 || postings.posted > 0) ->
                "Allowance delivery has no completed provisioning source"
            else -> return null
        }
        return CloseParticipationAssembler.incomplete(day, reason, runs.map { it.toString() } + postings.toString())
    }

    private fun assembleOrLog(day: LocalDate, due: Set<String>, runs: List<CloseJobRun>): CloseParticipationReport? =
        CloseParticipationAssembler.assemble(day, due, runs, skipReason = SKIP_REASON)
            ?: CloseParticipationAssembler.incomplete(
                day,
                "Lending due job has not completed",
                due.sorted() + runs.map { it.toString() },
            )

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
