// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The watchdog's contract is "never throw, always classify" — the ERROR-log side effects are
 * asserted operationally via Loki; here we pin that each state (absent, fresh-OK, stale,
 * fresh-ERROR) is readable without an exception, so a repo glitch can't kill the hourly tick.
 */
class TieOutFreshnessWatchdogTest {

    private val now = Instant.parse("2026-07-16T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val runs = mockk<TieOutRunRepository>()
    private val watchdog =
        TieOutFreshnessWatchdog(runs, clock, NoOpClusterLock(), domainMetrics = mockk(relaxed = true))

    private fun run(age: Duration, status: TieOutRunStatus) = TieOutRunRecord(
        id = UUID.randomUUID(),
        asOf = LocalDate.of(2026, 7, 15),
        runAt = now.minus(age),
        status = status,
        accountsChecked = 4,
        breaks = 0,
        errors = if (status == TieOutRunStatus.ERROR) 1 else 0,
    )

    @Test
    fun `handles absence of any run`(): Unit = runBlocking {
        coEvery { runs.findLatest() } returns null
        watchdog.checkFreshness() // must not throw; logs ERROR
    }

    @Test
    fun `fresh OK run passes quietly`(): Unit = runBlocking {
        coEvery { runs.findLatest() } returns run(Duration.ofHours(4), TieOutRunStatus.OK)
        watchdog.checkFreshness()
    }

    @Test
    fun `stale run is escalated without throwing`(): Unit = runBlocking {
        coEvery { runs.findLatest() } returns run(Duration.ofHours(26), TieOutRunStatus.OK)
        watchdog.checkFreshness()
    }

    @Test
    fun `fresh ERROR run is escalated without throwing`(): Unit = runBlocking {
        coEvery { runs.findLatest() } returns run(Duration.ofHours(4), TieOutRunStatus.ERROR)
        watchdog.checkFreshness()
    }
}
