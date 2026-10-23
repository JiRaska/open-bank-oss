// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The servicing posting loop (ADR-0028 Phase 2). On each tick it recognizes interest income for every
 * installment that has fallen due but is not yet accrued (IAS 1 accrual basis), booking each to the
 * ledger and flagging the row so the pass is idempotent. Cash repayment later settles the receivable
 * rather than re-recognizing income.
 *
 * Time-based recognition is decoupled from cash collection — that is exactly why this is a *scheduled*
 * pass rather than something driven off the (event-driven) repayment endpoint. The interval and batch
 * size are config-driven; `concurrentExecution = SKIP` keeps overlapping runs from racing.
 *
 * Day-close evidence (ADR-0342 D1), in `lending_close_job_run`, under the Prague accounting day the
 * pass worked as of:
 *  - the pass marks itself IN FLIGHT first, so the reporter never sends an earlier run's RAN for a day
 *    this pass is still changing;
 *  - it DRAINS: while a batch comes back truncated it runs another, up to [drainBudget] and
 *    [maxDrainRounds], so the batch cap alone does not leave due interest behind;
 *  - completed: RAN with installments and journals; still truncated after the budget: REFUSED
 *    `due installment(s) left unaccrued`; the ledger's closed-day 409: REFUSED with that reason; any
 *    other failure: REFUSED with a fixed category;
 *  - before today's pass it CATCHES UP every earlier day in the lookback that is still REFUSED as
 *    truncated, draining as of THAT day and re-recording it, so a truncated day becomes RAN once its
 *    remainder is accrued instead of staying unresolvable (the next pass would otherwise file under
 *    the next day). A day the catch-up still cannot finish stays REFUSED and the operator waiver is
 *    the remaining path.
 * [LendingCloseParticipationScheduler] folds those rows into the report.
 */
@ApplicationScoped
class InterestAccrualScheduler @Inject constructor(
    private val accrual: AccrueInterestUseCase,
    @ConfigProperty(name = "lending.servicing.accrual.batch-size", defaultValue = "500")
    private val batchSize: Int,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
    private val closeJobRuns: CloseJobRunPort,
    @ConfigProperty(name = "lending.servicing.accrual.drain-budget", defaultValue = "PT10M")
    private val drainBudget: Duration = DEFAULT_DRAIN_BUDGET,
    @ConfigProperty(name = "lending.servicing.accrual.max-drain-rounds", defaultValue = "100")
    private val maxDrainRounds: Int = DEFAULT_MAX_DRAIN_ROUNDS,
    @ConfigProperty(name = "lending.close.lookback-days", defaultValue = "3")
    private val lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
) {
    private val log = Logger.getLogger(InterestAccrualScheduler::class.java)

    // Nullable, not `lateinit`: the gauge is a diagnostic, and a money-path job must never fail
    // because its observability wiring was not initialised. `lateinit` turns a missed StartupEvent
    // into an UninitializedPropertyAccessException thrown from the middle of the run.
    private var liveness: WorkflowLivenessRecorder? = null

    // ADR-0160 mechanism 3. Registered once at startup (CDI beans are singletons), not per-run.
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
    }

    @Scheduled(
        every = "{lending.servicing.accrual.every}",
        delayed = "30s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun runAccrualPass(): Uni<Void> = Panache.withSession {
        // The Prague accounting day (ADR-0207), not the UTC date: the pass accrues what is due on the
        // day it reports for, and the evidence is recorded under that same date.
        val today = AccountingClock.bank(clock).today()
        catchUpTruncatedDays(today)
            .flatMap { passFor(today) }
            .invoke { outcome ->
                if (outcome.installmentsAccrued > 0) {
                    log.infof(
                        "interest accrual pass: %d installments accrued as of %s",
                        outcome.installmentsAccrued,
                        outcome.asOf,
                    )
                } else {
                    log.debugf("interest accrual pass: nothing due as of %s", outcome.asOf)
                }
                // ADR-0160 mechanism 3: a completed pass is a success even with zero work; a pass the
                // budget left truncated is not, and the failure path below never records one.
                if (!outcome.truncated) liveness?.recordSuccess()
            }
            .onFailure().invoke { e -> log.error("interest accrual pass failed", e) }
            .replaceWithVoid()
    }

    /** Mark in flight, drain as of [asOf], record the evidence under [asOf]; a failure is REFUSED. */
    private fun passFor(asOf: LocalDate): Uni<AccrualOutcome> =
        LendingCloseJobs.markStarted(closeJobRuns, clock, asOf, LendingCloseJobs.ACCRUAL)
            .flatMap { drain(asOf, clock.instant().plus(drainBudget), AccrualOutcome(asOf, 0), 1) }
            .call { outcome ->
                // A pass the budget left truncated has not finished the day: RAN here would let the
                // ledger tie the day out over interest that was not booked.
                LendingCloseJobs.record(
                    closeJobRuns,
                    clock,
                    outcome.asOf,
                    LendingCloseJobs.ACCRUAL,
                    items = outcome.installmentsAccrued,
                    journals = outcome.journalsPosted,
                    refused = if (outcome.truncated) 1 else 0,
                    reason = if (outcome.truncated) LendingCloseJobs.REASON_ACCRUAL_TRUNCATED else null,
                )
            }
            .onFailure().call { e -> recordRefusal(asOf, e) }

    /** Runs batches as of [asOf] while they come back truncated, within the time and round budget. */
    private fun drain(asOf: LocalDate, deadline: Instant, sum: AccrualOutcome, round: Int): Uni<AccrualOutcome> =
        accrual.accrueDueInterest(asOf, batchSize).flatMap { batch ->
            val total = AccrualOutcome(
                asOf = asOf,
                installmentsAccrued = sum.installmentsAccrued + batch.installmentsAccrued,
                journalsPosted = sum.journalsPosted + batch.journalsPosted,
                truncated = batch.truncated,
            )
            // A truncated batch that accrued nothing cannot make progress: stop rather than spin.
            val canContinue = batch.truncated &&
                batch.installmentsAccrued > 0 &&
                round < maxDrainRounds &&
                clock.instant().isBefore(deadline)
            if (canContinue) drain(asOf, deadline, total, round + 1) else Uni.createFrom().item(total)
        }

    /**
     * Re-drains every earlier day in the lookback still REFUSED as truncated, as of that day, and
     * re-records it (RAN once nothing is left). Never fails today's pass.
     */
    private fun catchUpTruncatedDays(today: LocalDate): Uni<Unit> =
        closeJobRuns.findBetween(today.minusDays(lookbackDays), today.minusDays(1))
            .map { runs ->
                runs.filter {
                    it.job == LendingCloseJobs.ACCRUAL && it.refusalReason == LendingCloseJobs.REASON_ACCRUAL_TRUNCATED
                }.map { it.businessDate }.distinct().sorted()
            }
            .flatMap { days ->
                if (days.isEmpty()) {
                    Uni.createFrom().item(Unit)
                } else {
                    log.infof("interest accrual: catching up %d truncated day(s): %s", days.size, days)
                    Multi.createFrom().iterable(days)
                        .onItem().transformToUniAndConcatenate { day ->
                            passFor(day).onFailure().recoverWithNull()
                        }
                        .collect().asList()
                        .replaceWith(Unit)
                }
            }
            .onFailure().recoverWithItem { e ->
                log.warnf("interest accrual catch-up skipped (%s)", e.javaClass.simpleName)
                Unit
            }

    /**
     * A failed pass is a non-RAN day (ADR-0342: "a run in which pairs failed for any reason other
     * than the closed-day 409 is reported non-RAN"). The pass stops at its first failed posting, so
     * the count is the one installment that stopped it; the rest are still due and the next pass
     * retries them.
     */
    private fun recordRefusal(asOf: LocalDate, e: Throwable): Uni<Unit> {
        val closed = LendingCloseJobs.isClosedDayRejection(e)
        return LendingCloseJobs.record(
            closeJobRuns,
            clock,
            asOf,
            LendingCloseJobs.ACCRUAL,
            items = 0,
            journals = 0,
            refused = 1,
            reason = if (closed) LendingCloseJobs.REASON_DAY_CLOSED else LendingCloseJobs.REASON_ACCRUAL_FAILED,
        )
    }

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "lending-interest-accrual"
        val DEFAULT_DRAIN_BUDGET: Duration = Duration.ofMinutes(10)
        const val DEFAULT_MAX_DRAIN_ROUNDS = 100
        const val DEFAULT_LOOKBACK_DAYS = 3L
    }
}
