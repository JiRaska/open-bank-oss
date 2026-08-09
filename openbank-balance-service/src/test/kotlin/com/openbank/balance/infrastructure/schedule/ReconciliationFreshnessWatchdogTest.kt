// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.out.ReconciliationRecordRepository
import com.openbank.balance.domain.reconciliation.CurrencyReconciliation
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The watchdog's contract is "never throw, always classify" — the ERROR-log side effects are
 * asserted operationally via Loki; here we pin that each state (absent, fresh-OK, stale) is
 * readable without an exception, so a repo glitch can't kill the hourly tick.
 */
class ReconciliationFreshnessWatchdogTest {

    private val now = Instant.parse("2026-07-18T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val records = mockk<ReconciliationRecordRepository>()
    private val watchdog =
        ReconciliationFreshnessWatchdog(records, clock, NoOpClusterLock(), domainMetrics = mockk(relaxed = true))

    private fun reportAge(age: Duration) = ReconciliationReport(
        asOf = LocalDate.of(2026, 7, 17),
        generatedAt = OffsetDateTime.ofInstant(now.minus(age), ZoneOffset.UTC),
        tolerance = BigDecimal.ZERO,
        currencies = listOf(
            CurrencyReconciliation(
                currency = "CZK",
                ledgerControlBalance = BigDecimal.TEN,
                subLedgerBookedSum = BigDecimal.TEN,
                difference = BigDecimal.ZERO,
                withinTolerance = true,
            ),
        ),
    )

    @Test
    fun `handles absence of any run`(): Unit = runBlocking {
        coEvery { records.findLatest() } returns null
        watchdog.checkFreshness() // must not throw; logs ERROR
    }

    @Test
    fun `fresh run passes quietly`(): Unit = runBlocking {
        coEvery { records.findLatest() } returns reportAge(Duration.ofHours(4))
        watchdog.checkFreshness()
    }

    @Test
    fun `stale run is escalated without throwing`(): Unit = runBlocking {
        coEvery { records.findLatest() } returns reportAge(Duration.ofHours(26))
        watchdog.checkFreshness()
    }
}
