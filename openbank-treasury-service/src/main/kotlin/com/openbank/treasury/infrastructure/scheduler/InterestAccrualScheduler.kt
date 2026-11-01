// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.treasury.application.port.`in`.AccrualRun
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.AccountingDayClosedException
import com.openbank.treasury.application.port.out.CloseJobRunPort
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.infrastructure.close.TreasuryCloseJobs
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Daily interest accrual for SETTLED deals (ADR-0315 D5). Runs hourly rather than once a day on
 * purpose: every pass posts only the days not yet accrued (the per-day ledger key makes a repeat a
 * replay), so a missed or failed run is caught up by the next one instead of leaving a gap.
 *
 * **Day-close evidence (ADR-0342 D1).** Each pass works as of ONE Prague accounting day, taken at its
 * start, marks itself in flight in `treasury_close_job_run` before doing any work, and records its
 * outcome under that day: the deals assessed as items, and a refusal when a deal failed to accrue
 * (the ledger's `409 ACCOUNTING_DAY_CLOSED` gets its own category), when the pass budget ran out with
 * deals left, or when the whole pass failed. A marker that cannot be set fails the pass: running
 * without it could let the reporter send an earlier pass's RAN for a day this pass is still changing.
 * The pass stops starting deals once `openbank.treasury.accrual.pass-budget` is spent; that bound is
 * what the reporter's stale threshold is derived from (`TreasuryCloseJobs.staleAfter`).
 *
 * A `suspend fun` on purpose: a plain `@Scheduled` method has no Vert.x context and a
 * `runBlocking` around reactive Panache throws HR000068 (#2148; check-no-runblocking-in-scheduled).
 */
@ApplicationScoped
class InterestAccrualScheduler @Inject constructor(
    private val deals: TreasuryDealUseCase,
    private val domainMetrics: DomainMetrics,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.treasury.accrual.enabled", defaultValue = "true")
    private val enabled: Boolean,
    private val closeJobRuns: CloseJobRunPort,
    @ConfigProperty(name = "openbank.treasury.accrual.pass-budget") private val passBudget: Duration,
    @ConfigProperty(name = "openbank.treasury.close.lookback-days") private val lookbackDays: Long,
    /** ADR-0344 D7: each pass records the ACCRUALS step of the bank day it covered; null only in unit tests. */
    private val dayCompletion: DayCompletionRepository? = null,
) {
    private val log: Logger = Logger.getLogger(InterestAccrualScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    /** Registered at boot, not lazily on first fire: an absent gauge is not a stale one (ADR-0237). */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        if (enabled) liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(
        every = "\${openbank.treasury.accrual.interval:1h}",
        delayed = "\${openbank.treasury.accrual.initial-delay:60s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "treasury-interest-accrual",
    )
    @Suppress("TooGenericExceptionCaught") // any marker failure must stop the pass, whatever its type
    suspend fun run() {
        if (!enabled) return
        // The Prague accounting day (ADR-0207), once: the accrual and its evidence agree on the day.
        val asOf = AccountingClock.bank(clock).today()
        try {
            closeJobRuns.markStarted(asOf, TreasuryCloseJobs.ACCRUAL, clock.instant()).awaitSuspending()
        } catch (e: Exception) {
            log.errorf("interest accrual pass not started: in-flight marker not recorded (%s)", e.javaClass.simpleName)
            return
        }
        runCatching { deals.accrueInterest(asOf, clock.instant().plus(passBudget)) }
            .onSuccess { r ->
                runCatching {
                    dayCompletion?.record(
                        DayStepRecord.of(
                            asOf,
                            DayStep.ACCRUALS,
                            r.journals,
                            r.failures.size,
                            clock.instant(),
                            refused = r.failures.count { it is AccountingDayClosedException },
                        ),
                    )
                }
                    .onFailure { log.error("could not record the ACCRUALS day-completion step", it) }
                r.failures.forEach { log.error("interest accrual could not accrue a deal", it) }
                if (r.failures.isEmpty() && r.unassessed == 0) liveness?.recordSuccess()
                if (r.journals > 0) log.infof("interest accrual posted %d journal(s)", r.journals)
                if (r.unassessed > 0) log.warnf("interest accrual pass budget spent: %d deal(s) left", r.unassessed)
                if (r.deferred > 0) {
                    log.infof("interest accrual deferred %d deal(s) with an open posting intent (#13883)", r.deferred)
                }
                recordEvidence(asOf, r)
                if (r.failures.isEmpty() && r.unassessed == 0) healEarlierDays(asOf)
            }
            .onFailure {
                log.error("interest accrual pass failed", it)
                TreasuryCloseJobs.record(
                    closeJobRuns,
                    clock,
                    asOf,
                    TreasuryCloseJobs.ACCRUAL,
                    items = 0,
                    refused = 1,
                    reason = TreasuryCloseJobs.REASON_PASS_FAILED,
                ).awaitSuspending()
            }
    }

    /**
     * A deal the ledger refused into a closed day is a close outcome with its own category; any other
     * failed deal, or deals the budget left for the next pass, make the day REFUSED too. A later
     * complete pass on the same day replaces the refusal (refused is the latest pass's).
     */
    private suspend fun recordEvidence(asOf: LocalDate, r: AccrualRun) {
        val closedDay = r.failures.count { TreasuryCloseJobs.isClosedDayRejection(it) }
        val otherFailed = r.failures.size - closedDay
        val reasons = listOfNotNull(
            if (closedDay > 0) "$closedDay ${TreasuryCloseJobs.REASON_DAY_CLOSED}" else null,
            if (otherFailed > 0) "$otherFailed ${TreasuryCloseJobs.REASON_DEAL_FAILED}" else null,
            if (r.unassessed > 0) "${r.unassessed} ${TreasuryCloseJobs.REASON_ACCRUAL_UNFINISHED}" else null,
            if (r.deferred > 0) "${r.deferred} ${TreasuryCloseJobs.REASON_ACCRUAL_DEFERRED}" else null,
        )
        TreasuryCloseJobs.record(
            closeJobRuns,
            clock,
            asOf,
            TreasuryCloseJobs.ACCRUAL,
            items = r.deals,
            // #13883: a deferred deal is assessed for liveness and healing (the pass did all it could),
            // but in the DAY report it is unaccrued, so the day is not reported RAN without it.
            refused = r.failures.size + r.unassessed + r.deferred,
            reason = reasons.joinToString("; ").ifBlank { null },
        ).awaitSuspending()
    }

    /**
     * A COMPLETE pass as of [asOf] has accrued every SETTLED deal through [asOf], which includes every
     * earlier day: whatever left an earlier day REFUSED (a failed deal, the budget, a closed day, a pass
     * that died) or never recorded (the pod was down all day) is now accrued. Each such day in the
     * lookback is re-recorded RAN (#13882 review; lending's catch-up does the same), so it does not stay
     * REFUSED or unreported for good. A day with a LIVE marker (inside the stale threshold) is left
     * alone: that pass is still changing it. A healed day already reported is re-reported; the ledger
     * stores a report for a closed day as late and never moves the day.
     */
    private suspend fun healEarlierDays(asOf: LocalDate) {
        val from = asOf.minusDays(lookbackDays)
        val to = asOf.minusDays(1)
        val now = clock.instant()
        val runs = closeJobRuns.findBetween(from, to).awaitSuspending()
            .filter { it.job == TreasuryCloseJobs.ACCRUAL }
            .associateBy { it.businessDate }
        val live = closeJobRuns.inFlightBetween(from, to).awaitSuspending()
            .filter { it.job == TreasuryCloseJobs.ACCRUAL && it.startedAt.plus(staleAfter).isAfter(now) }
            .map { it.businessDate }
            .toSet()
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .filter { day -> day !in live && (runs[day]?.refused ?: 1) > 0 }
            .forEach { day ->
                log.infof("treasury accrual: %s re-recorded complete by the pass as of %s", day, asOf)
                TreasuryCloseJobs.record(closeJobRuns, clock, day, TreasuryCloseJobs.ACCRUAL, items = 0)
                    .awaitSuspending()
            }
    }

    private val staleAfter: Duration get() = TreasuryCloseJobs.staleAfter(passBudget)

    private companion object {
        const val WORKFLOW_NAME = "treasury-interest-accrual"
        val EXPECTED_INTERVAL: Duration = Duration.ofHours(1)
    }
}
