// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.balance.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class LowBalanceAlertTest {
    private val rule = LowBalanceAlertRule(BigDecimal("100.00"), BigDecimal("20.00"), Duration.ofHours(24))
    private val now = Instant.parse("2026-10-05T12:00:00Z")

    @Test
    fun `opt in below threshold does not produce a false crossing`() {
        val state = LowBalanceAlertState.initial(BigDecimal("80.00"), rule)
        assertThat(state.evaluate(BigDecimal("80.00"), rule, now).emit).isFalse()
        assertThat(state.armed).isFalse()
    }

    @Test
    fun `one downward crossing emits once despite replay`() {
        val state = LowBalanceAlertState.initial(BigDecimal("130.00"), rule)
        val crossed = state.evaluate(BigDecimal("99.99"), rule, now)
        val replayed = crossed.state.evaluate(BigDecimal("99.99"), rule, now)

        assertThat(crossed.emit).isTrue()
        assertThat(crossed.state.generation).isEqualTo(1)
        assertThat(replayed.emit).isFalse()
        assertThat(replayed.state).isEqualTo(crossed.state)
    }

    @Test
    fun `rearm needs hysteresis and a fresh downward crossing`() {
        val crossed = LowBalanceAlertState.initial(BigDecimal("130.00"), rule)
            .evaluate(BigDecimal("90.00"), rule, now).state
        val wobble = crossed.evaluate(BigDecimal("110.00"), rule, now.plusSeconds(3600))
        val rearmed = wobble.state.evaluate(BigDecimal("120.00"), rule, now.plusSeconds(3600))
        val next = rearmed.state.evaluate(BigDecimal("90.00"), rule, now.plus(Duration.ofDays(2)))

        assertThat(wobble.state.armed).isFalse()
        assertThat(rearmed.state.armed).isTrue()
        assertThat(next.emit).isTrue()
        assertThat(next.state.generation).isEqualTo(2)
    }

    @Test
    fun `cooldown coalesces a second crossing`() {
        val crossed = LowBalanceAlertState.initial(BigDecimal("130.00"), rule)
            .evaluate(BigDecimal("90.00"), rule, now).state
        val rearmed = crossed.evaluate(BigDecimal("130.00"), rule, now.plusSeconds(60)).state
        val second = rearmed.evaluate(BigDecimal("80.00"), rule, now.plusSeconds(120))

        assertThat(second.emit).isFalse()
        assertThat(second.state.armed).isFalse()
        assertThat(second.state.generation).isEqualTo(1)
    }

    @Test
    fun `late event uses current balance and does not emit`() {
        val state = LowBalanceAlertState.initial(BigDecimal("130.00"), rule)
        assertThat(state.evaluate(BigDecimal("130.00"), rule, now).emit).isFalse()
    }
}
