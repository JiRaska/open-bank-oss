// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.application.port.`in`.ReconcileBalancesUseCase
import com.openbank.balance.domain.reconciliation.CurrencyReconciliation
import com.openbank.balance.domain.reconciliation.ReconciliationReport
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The scheduler's contract is "never throw, always run the tie-out via the injected clock's
 * date" — drift and error handling are asserted here without a live Postgres; the atomic
 * cross-pod exclusion itself is [ClusterLock]/`PostgresClusterLock`'s own concern, proven once
 * against a real database in `openbank-ledger-service`'s `ClusterLockIT`, not re-proven per
 * caller.
 */
class BalanceReconciliationSchedulerTest {

    private val today = LocalDate.of(2026, 7, 18)
    private val clock = Clock.fixed(Instant.parse("2026-07-18T22:00:00Z"), ZoneOffset.UTC)
    private val reconcile = mockk<ReconcileBalancesUseCase>()
    private val scheduler =
        BalanceReconciliationScheduler(reconcile, clock, NoOpClusterLock(), domainMetrics = mockk(relaxed = true))

    private fun report(hasDrift: Boolean) = ReconciliationReport(
        asOf = today,
        generatedAt = OffsetDateTime.now(clock),
        tolerance = BigDecimal.ZERO,
        currencies = listOf(
            CurrencyReconciliation(
                currency = "CZK",
                ledgerControlBalance = BigDecimal.TEN,
                subLedgerBookedSum = if (hasDrift) BigDecimal.ONE else BigDecimal.TEN,
                difference = if (hasDrift) BigDecimal("-9") else BigDecimal.ZERO,
                withinTolerance = !hasDrift,
            ),
        ),
    )

    @Test
    fun `runs the tie-out for today's date from the injected clock`(): Unit = runBlocking {
        coEvery { reconcile.reconcile(today) } returns report(hasDrift = false)
        scheduler.runDaily()
        coVerify(exactly = 1) { reconcile.reconcile(today) }
    }

    @Test
    fun `drift is logged without throwing`(): Unit = runBlocking {
        coEvery { reconcile.reconcile(today) } returns report(hasDrift = true)
        scheduler.runDaily() // must not throw
    }

    @Test
    fun `a use-case failure is swallowed so the scheduler never crashes`(): Unit = runBlocking {
        coEvery { reconcile.reconcile(today) } throws IllegalStateException("ledger unreachable")
        scheduler.runDaily() // must not throw
    }
}
