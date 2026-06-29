// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.retention

import com.openbank.kyc.application.port.out.KycCaseRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class KycRetentionSchedulerTest {

    private val kycCaseRepository = mockk<KycCaseRepository>()
    private val fixedClock = Clock.fixed(Instant.parse("2031-06-15T03:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `deletes erased KYC cases whose hold period has expired`(): Unit = runBlocking {
        // Must use calendar-year arithmetic (minusYears), not 365-day arithmetic, to match
        // the scheduler's AML-safe cutoff calculation.
        val expectedCutoff = LocalDate.of(2031, 6, 15).minusYears(5)
            .atStartOfDay(ZoneOffset.UTC).toInstant() // 2026-06-15T00:00:00Z
        coEvery { kycCaseRepository.deleteErasedCasesOlderThan(expectedCutoff) } returns 2L

        scheduler(retentionYears = 5).enforceRetention()

        coVerify(exactly = 1) { kycCaseRepository.deleteErasedCasesOlderThan(expectedCutoff) }
    }

    @Test
    fun `dry-run does not call the repository`(): Unit = runBlocking {
        scheduler(dryRun = true).enforceRetention()

        coVerify(exactly = 0) { kycCaseRepository.deleteErasedCasesOlderThan(any()) }
    }

    @Test
    fun `disabled scheduler does not call the repository`(): Unit = runBlocking {
        scheduler(enabled = false).enforceRetention()

        coVerify(exactly = 0) { kycCaseRepository.deleteErasedCasesOlderThan(any()) }
    }

    @Test
    fun `zero rows deleted is a no-op (no exception)`(): Unit = runBlocking {
        coEvery { kycCaseRepository.deleteErasedCasesOlderThan(any()) } returns 0L

        scheduler().enforceRetention()

        coVerify(exactly = 1) { kycCaseRepository.deleteErasedCasesOlderThan(any()) }
    }

    private fun scheduler(retentionYears: Long = 5, dryRun: Boolean = false, enabled: Boolean = true) =
        KycRetentionScheduler(
            kycCaseRepository = kycCaseRepository,
            clock = fixedClock,
            retentionYears = retentionYears,
            dryRun = dryRun,
            enabled = enabled,
        )
}
