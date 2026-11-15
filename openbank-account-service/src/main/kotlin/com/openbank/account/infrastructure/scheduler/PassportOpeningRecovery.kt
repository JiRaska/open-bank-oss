// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.port.out.PassportCommitment
import com.openbank.account.application.port.out.PassportOpeningJob
import com.openbank.account.application.usecase.AccountService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

@ApplicationScoped
class PassportOpeningRecovery(private val passport: PassportCommitment, private val accounts: AccountService) {
    @Scheduled(
        every = "{openbank.account.passport-recovery.interval:10s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "passport-opening-recovery",
    )
    suspend fun recoverDueOpenings() {
        passport.claimOpenings(BATCH_SIZE).forEach { recover(it) }
    }

    private suspend fun recover(job: PassportOpeningJob) {
        runCatching { accounts.recoverPassportOpening(job) }.onFailure { failure ->
            if (failure !is Exception || failure is CancellationException) throw failure
            // The lease expires so this job can be retried. Avoid logging request/response content.
            LOG.warnf("Passport opening recovery will retry (%s)", failure.javaClass.simpleName)
        }
    }

    private companion object {
        const val BATCH_SIZE = 8
        val LOG: Logger = Logger.getLogger(PassportOpeningRecovery::class.java)
    }
}
