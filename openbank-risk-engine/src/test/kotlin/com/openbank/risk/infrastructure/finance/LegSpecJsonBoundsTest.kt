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
        assertThat(
            LegSpecJson.toLegs(
                List(LegSpecJson.MAX_LEGS) {
                    leg()
                },
                LegDefaults(),
                LegBounds.PRICING_REQUEST,
            ),
        ).hasSize(8)
        assertThatThrownBy {
            LegSpecJson.toLegs(List(LegSpecJson.MAX_LEGS + 1) { leg() }, LegDefaults(), LegBounds.PRICING_REQUEST)
        }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("at most 8")
    }

    @Test
    fun `an accrual span over fifty years is refused, fifty is accepted`() {
        assertThat(
            LegSpecJson.toLegs(listOf(leg(end = "2076-01-10")), LegDefaults(), LegBounds.PRICING_REQUEST),
        ).hasSize(1)
        assertThatThrownBy {
            LegSpecJson.toLegs(listOf(leg(end = "2076-01-11")), LegDefaults(), LegBounds.PRICING_REQUEST)
        }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("exceeds 50 years")
    }

    @Test
    fun `no frequency finer than daily exists, and a daily fifty-year leg hits the library cap`() {
        assertThatThrownBy {
            LegSpecJson.toLegs(listOf(leg(frequency = "12H")), LegDefaults(), LegBounds.PRICING_REQUEST)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            LegSpecJson.toLegs(listOf(leg(frequency = "0D")), LegDefaults(), LegBounds.PRICING_REQUEST)
        }.isInstanceOf(IllegalArgumentException::class.java)
        val daily = LegSpecJson.toLegs(
            listOf(leg(end = "2076-01-10", frequency = "1D")),
            LegDefaults(),
            LegBounds.BOOKED,
        ).single()
        assertThatThrownBy { ScheduleGenerator.generate(daily) }.isInstanceOf(ScheduleTooLongException::class.java)
    }

    @Test
    fun `a BOOKED deal is not held to the pricing-edge bounds - nine legs over sixty years still build`() {
        val legs = LegSpecJson.toLegs(List(9) { leg(end = "2086-01-10") }, LegDefaults(), LegBounds.BOOKED)
        assertThat(legs).hasSize(9)
        assertThatThrownBy {
            LegSpecJson.toLegs(List(9) { leg(end = "2086-01-10") }, LegDefaults(), LegBounds.PRICING_REQUEST)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun floating(payLag: Int? = null, fixLag: Int? = null) = leg().copy(
        paymentLagBusinessDays = payLag,
        rate = RateDto(type = "FLOATING", index = "PRIBOR-3M", fixingLagBusinessDays = fixLag),
    )

    @Test
    fun `a payment lag over thirty business days is refused, negative too, zero to thirty accepted`() {
        listOf(31, Int.MAX_VALUE, -1).forEach { lag ->
            assertThatThrownBy {
                LegSpecJson.toLegs(listOf(floating(payLag = lag)), LegDefaults(), LegBounds.PRICING_REQUEST)
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("paymentLagBusinessDays")
        }
        listOf(0, 2, 30).forEach { lag ->
            assertThat(
                LegSpecJson.toLegs(listOf(floating(payLag = lag)), LegDefaults(), LegBounds.PRICING_REQUEST),
            ).hasSize(1)
        }
    }

    @Test
    fun `a fixing lag over thirty business days is refused, two accepted`() {
        listOf(31, Int.MAX_VALUE, -1).forEach { lag ->
            assertThatThrownBy {
                LegSpecJson.toLegs(listOf(floating(fixLag = lag)), LegDefaults(), LegBounds.PRICING_REQUEST)
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("fixingLagBusinessDays")
        }
        assertThat(LegSpecJson.toLegs(listOf(floating(fixLag = 2)), LegDefaults(), LegBounds.PRICING_REQUEST))
            .hasSize(1)
    }

    @Test
    fun `a BOOKED deal is not held to the lag bound`() {
        assertThat(LegSpecJson.toLegs(listOf(floating(payLag = 31, fixLag = 45)), LegDefaults(), LegBounds.BOOKED))
            .hasSize(1)
    }
}
