// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.treasury.application.port.`in`.NostroBreakSweep
import com.openbank.treasury.application.port.`in`.NostroBreakUseCase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class NostroBreakSchedulerTest {

    private val breaks = mockk<NostroBreakUseCase>()

    private fun sweepsThrough(at: String, expected: String): Unit = runBlocking {
        coEvery { breaks.sweep(any()) } returns NostroBreakSweep(0, emptyList(), 0, 0, 0)
        val clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC)
        NostroBreakScheduler(breaks, DomainMetrics(), SimpleMeterRegistry(), clock, true).run()
        coVerify(exactly = 1) { breaks.sweep(LocalDate.parse(expected)) }
    }

    @Test
    fun `after 22 00 UTC in summer the sweep runs for the Prague day`() {
        sweepsThrough("2026-07-15T22:30:00Z", "2026-07-16")
    }

    @Test
    fun `after 23 00 UTC in winter the sweep runs for the Prague day`() {
        sweepsThrough("2026-01-15T23:30:00Z", "2026-01-16")
    }
}
