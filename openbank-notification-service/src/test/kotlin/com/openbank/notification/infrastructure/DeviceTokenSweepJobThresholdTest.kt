// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The cut-off the nightly sweep hands the repository (ADR-0135, 90 days).
 *
 * Its sibling test covers the liveness heartbeat; this covers the only *value* the job computes.
 * Getting it wrong is silent in both directions — too old retires nothing, too recent logs out
 * active customers — and no metric distinguishes either from a healthy run.
 */
class DeviceTokenSweepJobThresholdTest {

    private val repo = mockk<DeviceTokenRepository>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)

    private fun jobAt(now: String) = DeviceTokenSweepJob().also {
        it.repo = repo
        it.clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC)
        it.domainMetrics = metrics
    }

    @Test
    fun `the threshold is exactly 90 days before the clock's now`(): Unit = runBlocking {
        val threshold = slot<Instant>()
        every { repo.sweepStale(capture(threshold)) } returns Uni.createFrom().item(0)

        jobAt("2026-09-05T03:00:00Z").sweepStaleTokens()

        assertThat(threshold.captured).isEqualTo(Instant.parse("2026-06-07T03:00:00Z"))
    }

    @Test
    fun `the threshold moves with the clock, so it is never a fixed date`(): Unit = runBlocking {
        val first = slot<Instant>()
        every { repo.sweepStale(capture(first)) } returns Uni.createFrom().item(0)
        jobAt("2026-09-05T03:00:00Z").sweepStaleTokens()
        val earlier = first.captured

        val second = slot<Instant>()
        every { repo.sweepStale(capture(second)) } returns Uni.createFrom().item(0)
        jobAt("2026-09-06T03:00:00Z").sweepStaleTokens()

        assertThat(second.captured).isEqualTo(earlier.plusSeconds(86_400))
    }

    @Test
    fun `a repository failure is swallowed - one bad night must not fail the scheduler`(): Unit = runBlocking {
        every { repo.sweepStale(any()) } returns Uni.createFrom().failure(IllegalStateException("db down"))

        // No assertion beyond "this returns": an escaping exception is a Quarkus Scheduler
        // failure, which is the alert-noise outcome the job's catch exists to avoid.
        jobAt("2026-09-05T03:00:00Z").sweepStaleTokens()
    }
}
