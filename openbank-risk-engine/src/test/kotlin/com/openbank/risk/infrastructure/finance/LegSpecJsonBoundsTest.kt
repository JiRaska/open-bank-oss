// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.finance

import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.libs.finance.schedule.ScheduleTooLongException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The pricing edge's request bounds (ADR-0344 D8): every breach is a 400-class IllegalArgumentException. */
class LegSpecJsonBoundsTest {
    private fun leg(end: String = "2031-01-10", frequency: String = "1Y") = LegDto(
        payReceive = "PAY",
        currency = "CZK",
        notional = BigDecimal("1000000"),
        accrualStart = "2026-01-10",
        accrualEnd = end,
        accrualFrequency = frequency,
        rate = RateDto(type = "FIXED", rate = BigDecimal("0.03")),
    )

    @Test
    fun `at most eight legs`() {
        assertThat(LegSpecJson.toLegs(List(LegSpecJson.MAX_LEGS) { leg() })).hasSize(8)
        assertThatThrownBy { LegSpecJson.toLegs(List(LegSpecJson.MAX_LEGS + 1) { leg() }) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("at most 8")
    }

    @Test
    fun `an accrual span over fifty years is refused, fifty is accepted`() {
        assertThat(LegSpecJson.toLegs(listOf(leg(end = "2076-01-10")))).hasSize(1)
        assertThatThrownBy { LegSpecJson.toLegs(listOf(leg(end = "2076-01-11"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("exceeds 50 years")
    }

    @Test
    fun `no frequency finer than daily exists, and a daily fifty-year leg hits the library cap`() {
        assertThatThrownBy {
            LegSpecJson.toLegs(listOf(leg(frequency = "12H")))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            LegSpecJson.toLegs(listOf(leg(frequency = "0D")))
        }.isInstanceOf(IllegalArgumentException::class.java)
        val daily = LegSpecJson.toLegs(listOf(leg(end = "2076-01-10", frequency = "1D"))).single()
        assertThatThrownBy { ScheduleGenerator.generate(daily) }.isInstanceOf(ScheduleTooLongException::class.java)
    }
}
