// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.libs.domain.calendar.AccountingClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The staged rollout is the part that has to be right (ADR-0207 D3). A day lock that refuses
 * correctly but is switched on blind is not an improvement — #1197 turned a money-path control
 * from Audit to Enforce on a false precondition and killed five workloads for four days.
 *
 * So these tests assert BOTH halves: that shadow mode measures without refusing, and that enforce
 * mode actually refuses. A test suite that only covered the second would let the service ship with
 * the lock silently enforcing.
 */
class AccountingDayLockTest {

    private val today = LocalDate.of(2026, 7, 31)
    private val yesterday = LocalDate.of(2026, 7, 30)
    private val clock = Clock.fixed(Instant.parse("2026-07-31T09:00:00Z"), ZoneOffset.UTC)

    private lateinit var repository: AccountingDayRepository
    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setup() {
        repository = mockk()
        registry = SimpleMeterRegistry()
    }

    private fun lock(mode: String) =
        AccountingDayLock(repository, AccountingClock.bank(clock), registry, mode)

    private fun day(date: LocalDate, status: AccountingDayStatus): AccountingDayRecord {
        var record = AccountingDayRecord.open(date, Instant.parse("2026-07-30T06:00:00Z"), "op")
        while (record.status != status) {
            record = record.transitionTo(record.status.next!!, "op", Instant.parse("2026-07-30T20:00:00Z"))
        }
        return record
    }

    private fun counterTotal(outcome: String): Double = registry.find("openbank.ledger.day_lock.decisions")
        .tag("outcome", outcome)
        .counters()
        .sumOf { it.count() }

    @Nested
    inner class ShadowMode {

        @Test
        fun `a posting into a tied-out day is NOT refused, only recorded`(): Unit = runBlocking {
            coEvery { repository.findByDate(yesterday) } returns day(yesterday, AccountingDayStatus.TIED_OUT)

            // No throw — that is the whole point of shadow mode.
            lock(AccountingDayLock.MODE_SHADOW).requireOpen(yesterday, AccountingDayLock.OPERATION_POSTING)

            assertThat(counterTotal("would_refuse")).isEqualTo(1.0)
            assertThat(counterTotal("refused")).isZero()
        }

        @Test
        fun `an open day is allowed and counted as allowed`(): Unit = runBlocking {
            coEvery { repository.findByDate(today) } returns day(today, AccountingDayStatus.OPEN)

            lock(AccountingDayLock.MODE_SHADOW).requireOpen(today, AccountingDayLock.OPERATION_POSTING)

            assertThat(counterTotal("allowed")).isEqualTo(1.0)
            assertThat(counterTotal("would_refuse")).isZero()
        }

        /**
         * Absence must not fail closed. Every environment has zero day rows until an operator opens
         * the first one; failing closed on absence would brick every posting the moment this ships.
         * It is counted separately so "no day records anywhere" is visible rather than assumed.
         */
        @Test
        fun `a day with no row is allowed and counted separately`(): Unit = runBlocking {
            coEvery { repository.findByDate(today) } returns null

            lock(AccountingDayLock.MODE_SHADOW).requireOpen(today, AccountingDayLock.OPERATION_POSTING)

            assertThat(counterTotal("no_day_record")).isEqualTo(1.0)
            assertThat(counterTotal("would_refuse")).isZero()
        }

        @Test
        fun `shadow mode does not claim to be enforcing`() {
            assertThat(lock(AccountingDayLock.MODE_SHADOW).enforcing).isFalse()
        }
    }

    @Nested
    inner class EnforceMode {

        @Test
        fun `a posting into a closed day is refused`(): Unit = runBlocking {
            coEvery { repository.findByDate(yesterday) } returns day(yesterday, AccountingDayStatus.LOCKED)

            assertThatThrownBy {
                runBlocking {
                    lock(AccountingDayLock.MODE_ENFORCE).requireOpen(yesterday, AccountingDayLock.OPERATION_POSTING)
                }
            }
                .isInstanceOf(ClosedAccountingDayException::class.java)
                .hasMessageContaining("LOCKED")

            assertThat(counterTotal("refused")).isEqualTo(1.0)
        }

        @Test
        fun `an open day still posts`(): Unit = runBlocking {
            coEvery { repository.findByDate(today) } returns day(today, AccountingDayStatus.OPEN)

            lock(AccountingDayLock.MODE_ENFORCE).requireOpen(today, AccountingDayLock.OPERATION_POSTING)

            assertThat(counterTotal("allowed")).isEqualTo(1.0)
        }

        /**
         * evaluate() never throws even under enforce — the reversal path depends on that, because a
         * reversal out of a closed day must be ROUTED, not refused.
         */
        @Test
        fun `evaluate reports a closed day without throwing`(): Unit = runBlocking {
            coEvery { repository.findByDate(yesterday) } returns day(yesterday, AccountingDayStatus.TIED_OUT)

            val decision = lock(AccountingDayLock.MODE_ENFORCE)
                .evaluate(yesterday, AccountingDayLock.OPERATION_REVERSAL)

            assertThat(decision.wouldRefuse).isTrue()
            assertThat(decision.status).isEqualTo(AccountingDayStatus.TIED_OUT)
        }
    }

    @Nested
    inner class OffMode {

        @Test
        fun `off does not even read the repository`(): Unit = runBlocking {
            // No stub for findByDate: if the lock touched it, mockk would fail the test.
            lock(AccountingDayLock.MODE_OFF).requireOpen(yesterday, AccountingDayLock.OPERATION_POSTING)

            assertThat(counterTotal("would_refuse")).isZero()
            assertThat(counterTotal("allowed")).isZero()
        }
    }

    @Nested
    inner class ForwardCorrection {

        @Test
        fun `corrections land on the latest open day`(): Unit = runBlocking {
            coEvery { repository.findLatestOpen() } returns day(today, AccountingDayStatus.OPEN)

            assertThat(lock(AccountingDayLock.MODE_ENFORCE).forwardCorrectionDate()).isEqualTo(today)
        }

        /**
         * With no day opened yet the correction date falls back to the current accounting day, so
         * reversals keep working exactly as they do today rather than depending on a rollout step.
         */
        @Test
        fun `with no open day it falls back to the current accounting day`(): Unit = runBlocking {
            coEvery { repository.findLatestOpen() } returns null

            assertThat(lock(AccountingDayLock.MODE_ENFORCE).forwardCorrectionDate()).isEqualTo(today)
        }
    }
}
