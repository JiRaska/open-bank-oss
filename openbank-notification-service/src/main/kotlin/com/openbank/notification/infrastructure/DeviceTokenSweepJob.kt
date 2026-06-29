// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Nightly sweep that retires push device tokens not refreshed within 90 days (ADR-0135).
 * A customer who hasn't opened the app in 90 days will no longer receive fan-out deliveries
 * until they re-register on next launch — this reduces delivery noise and limits the blast
 * radius of a token database compromise.
 *
 * The job is intentionally fire-and-forget (Uni.subscribe): a failure is logged but does not
 * surface as a Quarkus Scheduler failure (which would create alert noise for a non-critical job).
 */
@ApplicationScoped
class DeviceTokenSweepJob {

    @Inject
    lateinit var repo: DeviceTokenRepository

    @Inject
    lateinit var clock: Clock

    @Scheduled(cron = "0 0 3 * * ?", identity = "device-token-stale-sweep")
    fun sweepStaleTokens() {
        val threshold = Instant.now(clock).minus(STALE_DAYS, ChronoUnit.DAYS)
        repo.sweepStale(threshold)
            .subscribe().with(
                { count ->
                    if (count > 0) Log.infof("Swept %d stale device tokens (threshold %s)", count, threshold)
                },
                { err -> Log.errorf(err, "Failed to sweep stale device tokens") },
            )
    }

    companion object {
        private const val STALE_DAYS = 90L
    }
}
