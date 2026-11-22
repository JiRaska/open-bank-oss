// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.port.out.PassportCommitment
import com.openbank.account.application.port.out.PassportOpeningJob
import com.openbank.account.application.usecase.AccountService
import com.openbank.libs.observability.DomainMetrics
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

@Startup
@ApplicationScoped
class PassportOpeningRecovery(
    private val passport: PassportCommitment,
    private val accounts: AccountService,
    metrics: DomainMetrics,
    @ConfigProperty(name = "openbank.account.passport-recovery.interval", defaultValue = "10s")
    interval: Duration,
) {
    private val liveness = metrics.registerWorkflowLiveness("account-passport-opening-recovery", interval)

    @Scheduled(
        every = "{openbank.account.passport-recovery.interval:10s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "passport-opening-recovery",
    )
    suspend fun recoverDueOpenings() {
        var allSucceeded = true
        for (job in passport.claimOpenings(BATCH_SIZE)) {
            if (!recover(job)) allSucceeded = false
        }
        if (allSucceeded) liveness.recordSuccess()
    }

    private suspend fun recover(job: PassportOpeningJob): Boolean =
        runCatching { accounts.recoverPassportOpening(job) }.onFailure { failure ->
            if (failure !is Exception || failure is CancellationException) throw failure
            // The lease expires so this job can be retried. Avoid logging request/response content.
            LOG.warnf("Passport opening recovery will retry (%s)", failure.javaClass.simpleName)
        }.isSuccess

    private companion object {
        const val BATCH_SIZE = 8
        val LOG: Logger = Logger.getLogger(PassportOpeningRecovery::class.java)
    }
}
