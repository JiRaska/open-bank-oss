// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
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
) {
    private val log = Logger.getLogger(InterestAccrualScheduler::class.java)

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
            }
            .onFailure().invoke { e -> log.error("interest accrual pass failed", e) }
            .replaceWithVoid()
    }
}
