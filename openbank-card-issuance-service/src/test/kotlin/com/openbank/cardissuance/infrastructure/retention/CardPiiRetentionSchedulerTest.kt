// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.retention

import com.openbank.cardissuance.application.port.out.CardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CardPiiRetentionSchedulerTest {

    private val cardRepository = mockk<CardRepository>()
    private val fixedClock = Clock.fixed(Instant.parse("2031-06-15T03:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `anonymises card PII whose expiry date is more than or exactly retentionYears ago`(): Unit = runBlocking {
        // cutoff = 2031-06-15 − 5y = 2026-06-15; cards with expiryDate <= cutoff are anonymised,
        // including those that expired exactly on the cutoff date (GDPR Art.5 boundary inclusive).
        val expectedCutoff = LocalDate.of(2026, 6, 15)
        coEvery { cardRepository.anonymizeExpiredCardPii(expectedCutoff) } returns 3

        scheduler(retentionYears = 5).enforceRetention()

        coVerify(exactly = 1) { cardRepository.anonymizeExpiredCardPii(expectedCutoff) }
    }

    @Test
    fun `dry-run does not call the repository`(): Unit = runBlocking {
        scheduler(dryRun = true).enforceRetention()

        coVerify(exactly = 0) { cardRepository.anonymizeExpiredCardPii(any()) }
    }

    @Test
    fun `disabled scheduler does not call the repository`(): Unit = runBlocking {
        scheduler(enabled = false).enforceRetention()

        coVerify(exactly = 0) { cardRepository.anonymizeExpiredCardPii(any()) }
    }

    @Test
    fun `zero rows anonymised is a no-op (no exception)`(): Unit = runBlocking {
        coEvery { cardRepository.anonymizeExpiredCardPii(any()) } returns 0

        scheduler().enforceRetention()

        coVerify(exactly = 1) { cardRepository.anonymizeExpiredCardPii(any()) }
    }

    private fun scheduler(retentionYears: Long = 5, dryRun: Boolean = false, enabled: Boolean = true) =
        CardPiiRetentionScheduler(
            cardRepository = cardRepository,
            clock = fixedClock,
            retentionYears = retentionYears,
            dryRun = dryRun,
            enabled = enabled,
            domainMetrics = mockk(relaxed = true),
        )
}
