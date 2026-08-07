// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.usecase.SavingsProposalService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Sweeps PENDING withdrawal proposals whose window has closed into EXPIRED (ADR-0232 D8 / AC8).
 *
 * MUST be a `suspend fun`. A plain `@Scheduled` method carries no Vert.x context — Quarkus invokes
 * it on a bare executor-thread — so a body wrapping the reactive Panache repository in
 * `runBlocking` throws `HR000068` and the job aborts having done nothing, silently. Five schedulers
 * in this fleet had never once run for exactly that reason (#2148, #2187), which is also why
 * `SavingsProposalExpirySchedulerVertxContextIT` drives the REAL cron from a test profile instead
 * of calling this method directly: a direct call supplies the very context the scheduler does not,
 * so it passes against broken code.
 */
@ApplicationScoped
class SavingsProposalExpiryScheduler(private val proposalService: SavingsProposalService) {

    private val log: Logger = Logger.getLogger(SavingsProposalExpiryScheduler::class.java)

    @Scheduled(
        cron = "\${openbank.savings.proposal-expiry-cron:0 */10 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "savings-proposal-expiry",
    )
    suspend fun sweep() {
        val expired = runCatching { proposalService.expireStale() }
            .onFailure { log.error("savings withdrawal proposal expiry sweep failed", it) }
            .getOrDefault(0)
        if (expired > 0) log.infof("expired %d stale savings withdrawal proposal(s)", expired)
    }
}
