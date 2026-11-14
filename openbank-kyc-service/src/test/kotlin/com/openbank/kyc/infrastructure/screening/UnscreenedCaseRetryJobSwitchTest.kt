// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.screening

import com.openbank.kyc.application.UnscreenedCaseRetryService
import com.openbank.kyc.application.port.out.UnscreenedBacklog
import com.openbank.kyc.application.port.out.UnscreenedCaseStore
import com.openbank.libs.observability.DomainMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The disable switch, by effect: with it off no retry is attempted, with it on one is. Whether the
 * scheduler dispatches the method at all is `UnscreenedCaseRetrySchedulerIT`'s claim; this class
 * only decides what one tick does, which a direct call can establish.
 */
class UnscreenedCaseRetryJobSwitchTest {

    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val service = mockk<UnscreenedCaseRetryService>()
    private val store = mockk<UnscreenedCaseStore>()
    private val registry = mockk<Instance<MeterRegistry>> { every { isResolvable } returns false }

    private fun job(enabled: Boolean) = UnscreenedCaseRetryJob(
        service,
        store,
        Clock.fixed(now, ZoneOffset.UTC),
        enabled,
        Duration.ofMinutes(2),
        50,
        mockk<DomainMetrics>(relaxed = true),
        registry,
    )

    init {
        coEvery { service.retryBatch(any(), any(), any()) } returns UnscreenedCaseRetryService.Outcome(0, 0, 0)
        coEvery { store.backlog() } returns UnscreenedBacklog(0, null)
    }

    @Test
    fun `a disabled sweep retries nothing but still measures what is left unscreened`(): Unit = runBlocking {
        job(enabled = false).retryUnscreenedCases()

        coVerify(exactly = 0) { service.retryBatch(any(), any(), any()) }
        coVerify(exactly = 1) { store.backlog() }
    }

    @Test
    fun `an enabled sweep retries cases last attempted at least min-age ago, in a bounded batch`(): Unit = runBlocking {
        job(enabled = true).retryUnscreenedCases()

        coVerify(exactly = 1) { service.retryBatch(now, now.minus(Duration.ofMinutes(2)), 50) }
        coVerify(exactly = 1) { store.backlog() }
    }
}
