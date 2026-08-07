// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
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
 */
@ApplicationScoped
class InterestAccrualScheduler(
    private val accrual: AccrueInterestUseCase,
    @ConfigProperty(name = "lending.servicing.accrual.batch-size", defaultValue = "500")
    private val batchSize: Int,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
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
            .onFailure().invoke { e -> log.error("interest accrual pass failed", e) }
            .replaceWithVoid()
    }

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "lending-interest-accrual"
    }
}
