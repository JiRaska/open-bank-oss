// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.finance

import com.openbank.libs.finance.schedule.RollDirection
import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.libs.finance.schedule.StubType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** A leg with no stub is placed by the deal's roll direction, exactly as treasury stored it. */
class LegSpecJsonStubTest {
    // 2026-01-10 to 2027-03-10 quarterly leaves a short period, so the stub position is observable.
    private fun leg(stub: String?) = LegDto(
        payReceive = "PAY",
        currency = "CZK",
        notional = BigDecimal("1000000"),
        accrualStart = "2026-01-10",
        accrualEnd = "2027-03-10",
        accrualFrequency = "3M",
        stub = stub,
        rate = RateDto(type = "FIXED", rate = BigDecimal("0.03")),
    )

    private fun stubOf(stub: String?, direction: RollDirection) =
        LegSpecJson.toLegs(listOf(leg(stub)), LegDefaults(rollDirection = direction), LegBounds.BOOKED)
            .single().schedule.stub

    @Test
    fun `NONE, null and blank are all no stub`() {
        for (raw in listOf("NONE", "none", null, " ")) {
            assertThat(stubOf(raw, RollDirection.FROM_END)).isEqualTo(StubType.SHORT_INITIAL)
            assertThat(stubOf(raw, RollDirection.FROM_START)).isEqualTo(StubType.SHORT_FINAL)
        }
    }

    @Test
    fun `an explicit stub wins over the roll direction`() {
        assertThat(stubOf("LONG_FINAL", RollDirection.FROM_END)).isEqualTo(StubType.LONG_FINAL)
        assertThat(stubOf("SHORT_INITIAL", RollDirection.FROM_START)).isEqualTo(StubType.SHORT_INITIAL)
    }

    @Test
    fun `the default roll direction is treasury's FROM_END`() {
        assertThat(LegDefaults().rollDirection).isEqualTo(RollDirection.FROM_END)
        assertThat(LegDefaults.parseRollDirection(null)).isEqualTo(RollDirection.FROM_END)
        assertThat(LegDefaults.parseRollDirection("from_start")).isEqualTo(RollDirection.FROM_START)
    }

    @Test
    fun `FROM_START and FROM_END derive different schedules for the same leg`() {
        fun dates(d: RollDirection) = ScheduleGenerator.generate(
            LegSpecJson.toLegs(listOf(leg("NONE")), LegDefaults(rollDirection = d), LegBounds.BOOKED).single(),
        ).toString()
        assertThat(dates(RollDirection.FROM_START)).isNotEqualTo(dates(RollDirection.FROM_END))
    }

    @Test
    fun `a genuinely unknown stub still fails`() {
        assertThatThrownBy { stubOf("SIDEWAYS", RollDirection.FROM_END) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("stub")
        assertThatThrownBy { LegDefaults.parseRollDirection("SIDEWAYS") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
