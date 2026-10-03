// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.settlement.application.port.out.SettlementOperatorApprovalPurge
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OperatorApprovalRetentionTest {

    private val now = Instant.parse("2026-10-03T03:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** In-memory evidence table: deletes the oldest rows strictly before the cutoff. */
    private class FakePurge(var expiresAt: MutableList<OffsetDateTime>) : SettlementOperatorApprovalPurge {
        val calls = mutableListOf<Pair<OffsetDateTime, Int>>()
        override suspend fun purgeTerminalExpiredBefore(cutoff: OffsetDateTime, batchSize: Int): Int {
            calls += cutoff to batchSize
            val victims = expiresAt.filter { it.isBefore(cutoff) }.sorted().take(batchSize)
            expiresAt.removeAll(victims)
            return victims.size
        }
    }

    private fun retention(purge: FakePurge, days: Long = 1826, batch: Int = 2, maxBatches: Int = 10) =
        OperatorApprovalRetention(purge, clock, days, batch, maxBatches)

    @Test
    fun `cutoff is exactly the retention period before now`() {
        assertThat(retention(FakePurge(mutableListOf())).cutoff())
            .isEqualTo(OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(1826))
    }

    @Test
    fun `deletes only rows older than the cutoff and keeps the boundary row`(): Unit = runBlocking {
        val cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(1826)
        val rows = mutableListOf(cutoff.minusDays(1), cutoff.minusSeconds(1), cutoff, cutoff.plusDays(1))
        val purge = FakePurge(rows)
        assertThat(retention(purge).purgeExpired()).isEqualTo(2)
        assertThat(rows).containsExactly(cutoff, cutoff.plusDays(1))
    }

    @Test
    fun `batches until a short batch and stops there`(): Unit = runBlocking {
        val old = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(4000)
        val purge = FakePurge(MutableList(5) { old.plusMinutes(it.toLong()) })
        assertThat(retention(purge, batch = 2).purgeExpired()).isEqualTo(5)
        // 2 + 2 + 1: the short third batch ends the run without a wasted fourth query.
        assertThat(purge.calls).hasSize(3)
        assertThat(purge.calls.map { it.second }).containsOnly(2)
    }

    @Test
    fun `one run is bounded by max batches and the next run continues`(): Unit = runBlocking {
        val old = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(4000)
        val purge = FakePurge(MutableList(10) { old.plusMinutes(it.toLong()) })
        val retention = retention(purge, batch = 2, maxBatches = 3)
        assertThat(retention.purgeExpired()).isEqualTo(6)
        assertThat(retention.purgeExpired()).isEqualTo(4)
        assertThat(retention.purgeExpired()).isZero()
    }

    @Test
    fun `nothing past retention is an idempotent no-op`(): Unit = runBlocking {
        val purge = FakePurge(mutableListOf(OffsetDateTime.ofInstant(now, ZoneOffset.UTC)))
        assertThat(retention(purge).purgeExpired()).isZero()
        assertThat(retention(purge).purgeExpired()).isZero()
        assertThat(purge.calls).hasSize(2)
    }

    @Test
    fun `the cutoff is in the past for the shortest retention so a live approval can never match`() {
        val cutoff = retention(FakePurge(mutableListOf()), days = 1).cutoff()
        assertThat(cutoff).isBefore(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        // A live approval has expiresAt >= now > cutoff, and the purge deletes only expiresAt < cutoff.
        assertThat(OffsetDateTime.ofInstant(now, ZoneOffset.UTC).isBefore(cutoff)).isFalse()
    }

    @Test
    fun `an expired pending approval is purged on its expiry like any terminal row`(): Unit = runBlocking {
        // The use case is status-agnostic by design: expiry, not status, decides terminality.
        val cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(1826)
        val expiredPendingOld = cutoff.minusDays(1)
        val expiredPendingRecent = cutoff.plusDays(1)
        val livePending = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).plusHours(1)
        val rows = mutableListOf(expiredPendingOld, expiredPendingRecent, livePending)
        assertThat(retention(FakePurge(rows)).purgeExpired()).isEqualTo(1)
        assertThat(rows).containsExactly(expiredPendingRecent, livePending)
    }

    @Test
    fun `non-positive configuration is refused at construction`() {
        assertThatThrownBy { retention(FakePurge(mutableListOf()), days = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { retention(FakePurge(mutableListOf()), batch = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
