// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.party.infrastructure.metrics

import com.openbank.libs.observability.DomainMetrics
import com.openbank.party.application.port.out.PartyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * #5698: parties stranded in `PENDING_KYC` were invisible because a swallowed failure produces no
 * error to count. These tests assert the three properties that make the gauge a usable signal —
 * it reports the aged count, it EXCLUDES healthy in-flight onboarding, and it refuses to report a
 * stale value when the query fails.
 */
class PartyPendingKycStalledGaugeTest {

    private val now = Instant.parse("2026-08-19T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val stallAfter = Duration.ofHours(24)

    private fun gauge(repo: PartyRepository, metrics: DomainMetrics) =
        PartyPendingKycStalledGauge(repo, metrics, clock, stallAfter)

    @Test
    fun `the registered supplier tracks the refreshed count`(): Unit = runBlocking {
        val repo = mockk<PartyRepository>()
        val metrics = mockk<DomainMetrics>()
        val supplier = slot<() -> Number>()
        val stage = slot<String>()
        every { metrics.registerOnboardingStalled(capture(stage), capture(supplier)) } returns Unit
        coEvery { repo.countPendingKycOlderThan(any()) } returns 10L

        val g = gauge(repo, metrics)
        g.register()

        assertThat(stage.captured).isEqualTo("party-pending-kyc")
        // Micrometer reads the cached value, so before any refresh it must be 0 rather than blocking.
        assertThat(supplier.captured().toLong()).isZero()
        g.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(10L)
    }

    @Test
    fun `the cutoff excludes parties younger than the stall window`(): Unit = runBlocking {
        // The property that stops this alarming on healthy traffic: PENDING_KYC is the CORRECT
        // state for a freshly created party, so only the age cutoff separates a stall from normal
        // onboarding in flight. Asserts the exact instant handed to the query.
        val repo = mockk<PartyRepository>()
        val metrics = mockk<DomainMetrics>()
        every { metrics.registerOnboardingStalled(any(), any()) } returns Unit
        val cutoff = slot<Instant>()
        coEvery { repo.countPendingKycOlderThan(capture(cutoff)) } returns 0L

        gauge(repo, metrics).refresh()

        assertThat(cutoff.captured).isEqualTo(now.minus(stallAfter))
        assertThat(cutoff.captured).isBefore(now)
    }

    @Test
    fun `a failing query propagates instead of freezing the gauge at a healthy-looking value`(): Unit = runBlocking {
        // The #5698 shape applied to the detector itself: if the refresh swallowed its own failure,
        // the gauge would hold its last value — most likely 0 — and read as healthy forever while
        // measuring nothing. The scheduler must see the throw.
        val repo = mockk<PartyRepository>()
        val metrics = mockk<DomainMetrics>()
        val supplier = slot<() -> Number>()
        every { metrics.registerOnboardingStalled(any(), capture(supplier)) } returns Unit
        coEvery { repo.countPendingKycOlderThan(any()) } returnsMany listOf(7L) andThenThrows
            IllegalStateException("db down")

        val g = gauge(repo, metrics)
        g.register()
        g.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(7L)

        assertThatThrownBy { runBlocking { g.refresh() } }
            .isInstanceOf(IllegalStateException::class.java)
        // and the last good value is still what is served — not silently reset to a healthy 0
        assertThat(supplier.captured().toLong()).isEqualTo(7L)
        coVerify(exactly = 2) { repo.countPendingKycOlderThan(any()) }
    }
}
