// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root for details.

package com.openbank.agent.application

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

class CharterRateLimiterTest {

    private fun limiter(tokensPerRun: Long = Long.MAX_VALUE, runsPerDay: Long = Long.MAX_VALUE): CharterRateLimiter {
        val registry = mockk<CharterRegistry>()
        every { registry.tokensPerRun(any()) } returns tokensPerRun
        every { registry.runsPerDay(any()) } returns runsPerDay
        return CharterRateLimiter(Clock.systemUTC()).also { it.registry = registry }
    }

    @Test
    fun `no limit configured returns no messages`() {
        val l = limiter()
        assertThat(l.checkRunsPerDay("any-agent")).isNull()
        assertThat(l.checkTokensPerRun("any-agent", Long.MAX_VALUE)).isNull()
    }

    @Test
    fun `token check passes when usage is under limit`() {
        val l = limiter(tokensPerRun = 100_000)
        assertThat(l.checkTokensPerRun("ui-assistant", 500)).isNull()
    }

    @Test
    fun `token check returns warning when usage exceeds limit`() {
        val l = limiter(tokensPerRun = 100_000)
        val msg = l.checkTokensPerRun("ui-assistant", 200_000)
        assertThat(msg).isNotNull().contains("200000").contains("charter limit")
    }

    @Test
    fun `runs per day allows first run`() {
        val l = limiter(runsPerDay = 5)
        assertThat(l.checkRunsPerDay("ui-assistant")).isNull()
    }

    @Test
    fun `runs per day blocks when exceeded`() {
        val l = limiter(runsPerDay = 2)
        l.checkRunsPerDay("ui-assistant") // run 1
        l.checkRunsPerDay("ui-assistant") // run 2
        val msg = l.checkRunsPerDay("ui-assistant") // run 3 → over limit
        assertThat(msg).isNotNull().contains("2").contains("Quota")
    }
}
