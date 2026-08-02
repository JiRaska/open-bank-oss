// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.ClosedPeriodRepository
import com.openbank.ledger.domain.model.AccountingPeriod
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.ledger.domain.model.ClosedPeriodStatus
import com.openbank.ledger.domain.model.PeriodType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Same staged-rollout discipline as [AccountingDayLockTest], and asserted for the same reason: a
 * lock that refuses correctly but is switched on blind is not an improvement (#1197). Both halves
 * are covered — shadow measures without refusing, enforce actually refuses — because a suite that
 * only proved the second would let the service ship silently enforcing.
 */
class PeriodFreezeLockTest {

    private val may = PeriodType.MONTH.of(LocalDate.of(2026, 5, 1))
    private val inMay = LocalDate.of(2026, 5, 17)

    private lateinit var repository: ClosedPeriodRepository
    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setup() {
        repository = mockk()
        registry = SimpleMeterRegistry()
    }

    private fun lock(mode: String) = PeriodFreezeLock(repository, registry, mode)

    private fun frozen(period: AccountingPeriod) = ClosedPeriodRecord(
        id = UUID.randomUUID(),
        period = period,
        status = ClosedPeriodStatus.FROZEN,
        computedAt = Instant.parse("2026-06-01T06:00:00Z"),
        totalDebits = BigDecimal("1000"),
        totalCredits = BigDecimal("1000"),
        accountCount = 2,
        contentHash = "deadbeef",
        draftedBy = "maker",
        frozenBy = "checker",
        frozenAt = Instant.parse("2026-06-01T07:00:00Z"),
    )

    private fun counterTotal(outcome: String): Double = registry.find("openbank.ledger.period_lock.decisions")
        .tag("outcome", outcome)
        .counters()
        .sumOf { it.count() }

    @Nested
    inner class ShadowMode {

        @Test
        fun `a posting into a frozen period is NOT refused, only recorded`(): Unit = runBlocking {
            coEvery { repository.findFrozenContaining(inMay) } returns frozen(may)

            lock(PeriodFreezeLock.MODE_SHADOW).requireOpen(inMay, "posting")

            assertThat(counterTotal("would_refuse")).isEqualTo(1.0)
            assertThat(counterTotal("refused")).isZero()
        }

        @Test
        fun `a date in no frozen period is allowed`(): Unit = runBlocking {
            coEvery { repository.findFrozenContaining(inMay) } returns null

            lock(PeriodFreezeLock.MODE_SHADOW).requireOpen(inMay, "posting")

            assertThat(counterTotal("allowed")).isEqualTo(1.0)
        }

        @Test
        fun `shadow does not claim to be enforcing`() {
            assertThat(lock(PeriodFreezeLock.MODE_SHADOW).enforcing).isFalse()
        }
    }

    @Nested
    inner class EnforceMode {

        @Test
        fun `a posting into a frozen period is refused, naming the period`(): Unit = runBlocking {
            coEvery { repository.findFrozenContaining(inMay) } returns frozen(may)

            assertThatThrownBy {
                runBlocking { lock(PeriodFreezeLock.MODE_ENFORCE).requireOpen(inMay, "posting") }
            }
                .isInstanceOf(FrozenPeriodException::class.java)
                .hasMessageContaining("MONTH:2026-05")
                .hasMessageContaining("current open period")

            assertThat(counterTotal("refused")).isEqualTo(1.0)
        }

        /**
         * evaluate() never throws even under enforce — the reversal path depends on it, because a
         * reversal out of a sealed period must be routed forward rather than refused.
         */
        @Test
        fun `evaluate reports the frozen period without throwing`(): Unit = runBlocking {
            coEvery { repository.findFrozenContaining(inMay) } returns frozen(may)

            val result = lock(PeriodFreezeLock.MODE_ENFORCE).evaluate(inMay, "reversal")

            assertThat(result?.period?.label).isEqualTo("MONTH:2026-05")
        }
    }

    @Nested
    inner class OffMode {

        @Test
        fun `off does not even read the repository`(): Unit = runBlocking {
            // No stub for findFrozenContaining: if the lock touched it, mockk would fail the test.
            lock(PeriodFreezeLock.MODE_OFF).requireOpen(inMay, "posting")

            assertThat(counterTotal("allowed")).isZero()
            assertThat(counterTotal("would_refuse")).isZero()
        }
    }

    @Nested
    inner class NarrowestPeriodWins {

        /**
         * The repository is asked for the NARROWEST frozen period containing the date; this test
         * pins the contract the lock relies on — the message must name the boundary that actually
         * sealed the date, because "the year is frozen" sends an operator to the wrong remedy when
         * it was the month.
         */
        @Test
        fun `the refusal names whichever period the repository reports`(): Unit = runBlocking {
            coEvery { repository.findFrozenContaining(inMay) } returns frozen(PeriodType.YEAR.of(inMay))

            assertThatThrownBy {
                runBlocking { lock(PeriodFreezeLock.MODE_ENFORCE).requireOpen(inMay, "posting") }
            }.hasMessageContaining("YEAR:2026")
        }
    }
}
