// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.scheduler

import com.openbank.loyalty.application.usecase.ExpireLeavesUseCase
import com.openbank.loyalty.application.usecase.ProvisioningSummaryUseCase
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Runs expiry and the provisioning summary.
 *
 * Both are `suspend fun`. A plain `@Scheduled` method runs on a bare executor thread with no
 * Vert.x context, so a reactive Panache call inside `runBlocking` throws HR000068 and the job
 * aborts silently having done nothing. That is not hypothetical here — five schedulers in this
 * fleet shipped that way and had never once run.
 *
 * A test that calls these methods directly cannot see that class of bug, because the direct call
 * supplies the very context the scheduler does not. `LeafExpirySchedulerVertxContextIT` drives the
 * real cron through a shrunk expression instead.
 */
@ApplicationScoped
class LeafExpirySweep(private val expire: ExpireLeavesUseCase, private val provisioning: ProvisioningSummaryUseCase) {
    @Scheduled(
        cron = "\${openbank.loyalty.expiry-cron:0 15 2 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "loyalty-leaf-expiry-sweep",
    )
    suspend fun expireLots() {
        val expired = expire.sweep()
        // Logged at info even when zero: "the sweep ran and found nothing" and "the sweep did not
        // run" are different facts, and only the first one leaves a line.
        log.infof("loyalty.expiry.sweep expired_lots=%d", expired)
    }

    @Scheduled(
        cron = "\${openbank.loyalty.provisioning-cron:0 30 2 * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "loyalty-provisioning-summary",
    )
    suspend fun publishProvisioningSummary() {
        val summary = provisioning.summarise()
        log.infof("loyalty.provisioning.summary at=%s outstanding_leaves=%d", summary.at, summary.outstandingLeaves)
    }

    private companion object {
        private val log: Logger = Logger.getLogger(LeafExpirySweep::class.java)
    }
}
