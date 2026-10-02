// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.integration

import io.quarkus.test.Mock
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Starts the business date of every `@QuarkusTest` at 2030-01-01.
 *
 * `POST /snapshots` rejects an as-of after the current business date, and these ITs deliberately
 * use as-of dates spread over 2026-2028 (period calendars, forecast horizons). Against the real
 * clock they would turn red one by one as the wall clock passed each date — or, today, because
 * the dates are still ahead of it. A fixed clock keeps them deterministic. (The guard itself is
 * proven in `SnapshotServiceTest` and `RiskSnapshotApiIT`.)
 */
object FixedClockForIntegrationTests {
    val BUSINESS_NOW: Instant = Instant.parse("2030-01-01T12:00:00Z")

    /**
     * Real time shifted to start at [BUSINESS_NOW] — it still ADVANCES, because "newest first" list
     * ordering is by `recordedAt` and a frozen clock would tie every run.
     */
    val CLOCK: Clock = Clock.offset(Clock.system(ZoneOffset.UTC), Duration.between(Instant.now(), BUSINESS_NOW))
}

class FixedClockProducer {
    @Mock
    @Produces
    @Singleton
    fun clock(): Clock = FixedClockForIntegrationTests.CLOCK
}
