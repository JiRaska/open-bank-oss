// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.domain.BillingAssessment
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Assesses AND commits the intent-to-post for one account/cycle/currency (ADR-0143 phase 2c),
 * on top of the pure [FeeAssessmentService]. This is the seam the REST `POST /fees/post` endpoint
 * and the scheduled cycle trigger both call through: it is what actually persists the assessment
 * and appends the atomic outbox row — [FeeAssessmentService.assess] alone computes but does not
 * durably commit anything.
 *
 * **Idempotent** (ADR-0143 step 1): re-running the same `(cycleId, accountId, currency)` returns
 * the previously persisted assessment rather than assessing (and posting) again — a fee already
 * marked PENDING/POSTED/FAILED never gets a second outbox row, since the whole assessment row is
 * looked up first.
 *
 * **Concurrent-call race (fix-review finding):** [BillingAssessmentRepository.findExisting] then
 * [BillingAssessmentRepository.persistWithPostingIntent] is a check-then-act pair — two
 * concurrent calls for the same key can both observe "no existing row" here. That race is closed
 * one layer down: `BillingAssessmentRepositoryImpl.persistWithPostingIntent` catches the DB's
 * unique-constraint conflict and recovers into the same idempotent-replay return value, so this
 * method never needs its own retry — the repository already guarantees at most one persisted
 * assessment per key regardless of how many callers race here.
 */
@ApplicationScoped
class BillingCycleService(
    private val feeAssessmentService: FeeAssessmentService,
    private val repository: BillingAssessmentRepository,
) {
    private val log = Logger.getLogger(BillingCycleService::class.java)

    suspend fun assessAndPost(cycleId: String, accountId: String, currency: String): BillingAssessment {
        repository.findExisting(cycleId, accountId, currency)?.let {
            log.debugf(
                "billing cycle %s account %s currency %s already assessed — returning existing (idempotent replay)",
                cycleId,
                accountId,
                currency,
            )
            return it
        }
        val assessment = feeAssessmentService.assess(cycleId, accountId, currency)
        return repository.persistWithPostingIntent(assessment)
    }

    /**
     * Runs the cycle for a batch of accounts, e.g. from the scheduled trigger
     * ([com.openbank.billing.infrastructure.scheduler.BillingCycleScheduler]). Continues past a
     * single account's failure (logged) so one bad account never blocks the rest of the batch —
     * mirrors `InterestService.accrueAll`'s per-account isolation intent.
     */
    suspend fun runCycle(cycleId: String, accountIds: List<String>, currency: String): Int {
        var processed = 0
        for (accountId in accountIds) {
            runCatching { assessAndPost(cycleId, accountId, currency) }
                .onSuccess { processed++ }
                .onFailure { ex ->
                    log.errorf(
                        ex,
                        "billing cycle %s account %s currency %s failed — continuing with the rest of the batch",
                        cycleId,
                        accountId,
                        currency,
                    )
                }
        }
        return processed
    }
}
