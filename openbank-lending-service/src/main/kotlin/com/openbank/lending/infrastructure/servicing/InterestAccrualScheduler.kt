// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
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
 * Every pass records its day-close evidence (ADR-0342 D1) in `lending_close_job_run`: a completed
 * pass as RAN with the installments it recognized and the journals it booked; a pass the ledger
 * refused with the closed-day 409 (D4) as REFUSED with that reason; any other failure as REFUSED
 * with a fixed category, so the day holds instead of tying out over interest that was not booked.
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
        accrual.accrueDueInterest(LocalDate.now(clock), batchSize)
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
                // ADR-0160 mechanism 3: record after the success path — zero work is a legitimate
                // success (nothing due), never record in the failure path below.
                liveness?.recordSuccess()
            }
            .call { outcome ->
                LendingCloseJobs.record(
                    closeJobRuns,
                    clock,
                    LendingCloseJobs.ACCRUAL,
                    items = outcome.installmentsAccrued,
                    journals = outcome.journalsPosted,
                )
            }
            .onFailure().invoke { e -> log.error("interest accrual pass failed", e) }
            .onFailure().call { e -> recordRefusal(e) }
            .replaceWithVoid()
    }

    /**
     * A failed pass is a non-RAN day (ADR-0342: "a run in which pairs failed for any reason other
     * than the closed-day 409 is reported non-RAN"). The pass stops at its first failed posting, so
     * the count is the one installment that stopped it; the rest are still due and the next pass
     * retries them.
     */
    private fun recordRefusal(e: Throwable): Uni<Unit> {
        val closed = LendingCloseJobs.isClosedDayRejection(e)
        return LendingCloseJobs.record(
            closeJobRuns,
            clock,
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
    }
}
