// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.scheduler

import com.openbank.cardprocessing.application.port.`in`.CardProcessingUseCase
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Releases holds no acquirer ever presented against.
 *
 * Without this, an approved authorisation that is never cleared holds the customer's money for ever
 * — a permanent freeze with no error anywhere, which nothing in a health probe can see.
 *
 * A **`suspend fun`**, for the reason in the outbox dispatcher's KDoc: a plain `@Scheduled` method
 * has no Vert.x context, so a reactive repository call from one throws `HR000068` and the sweep
 * silently never runs. A test that calls this method directly cannot catch that — the direct call
 * supplies the very context the scheduler does not — so the coverage that matters is a
 * `@TestProfile` that re-enables the scheduler and shrinks the cron.
 */
@ApplicationScoped
class HoldExpirySweep(
    private val useCase: CardProcessingUseCase,
    @ConfigProperty(name = "openbank.card-processing.hold-sweep-batch", defaultValue = "200")
    private val batchSize: Int,
) {
    private val log = Logger.getLogger(HoldExpirySweep::class.java)

    @Scheduled(
        cron = "\${openbank.card-processing.hold-sweep-cron:0 */15 * * * ?}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "card-processing-hold-expiry",
    )
    suspend fun sweep() {
        val released = useCase.releaseExpiredHolds(batchSize)
        if (released > 0) log.infof("released %d expired card hold(s)", released)
    }
}
