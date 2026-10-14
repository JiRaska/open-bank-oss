// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.simulation

import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.infrastructure.pack.JurisdictionPackLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class RetirementProjectionTest {

    private val packs = JurisdictionPackLoader.loadRegistry()
    private val dps = packs.resolve("CZ", ProductLine.DPS, LocalDate.parse("2026-10-09"))
    private val dip = packs.resolve("CZ", ProductLine.DIP, LocalDate.parse("2026-10-09"))

    private val flat = StrategyAssumption("FLAT", 1, BigDecimal.ZERO)
    private val growth = StrategyAssumption("GROWTH", 5, BigDecimal("0.05"))

    @Test
    fun `at zero return the projection is exactly what was paid in, including the pack's matching`() {
        val p = RetirementProjection.project(
            dps,
            listOf(flat),
            ProjectionRequest(BigDecimal("1700"), BigDecimal.ZERO, 10),
        )
            .single()
        // CZ DPS: 1 700 a month earns the capped 340 state contribution, for 120 months.
        assertThat(p.ownContributions).isEqualByComparingTo("204000.00")
        assertThat(p.stateIncentives).isEqualByComparingTo("40800.00")
        assertThat(p.projectedValue).isEqualByComparingTo("244800.00")
    }

    @Test
    fun `a positive assumed return compounds above the inflows, and a riskier strategy is not equal to a safer one`() {
        val (zero, five) = RetirementProjection.project(
            dps,
            listOf(flat, growth),
            ProjectionRequest(BigDecimal("1000"), BigDecimal("500"), 20),
        )
        val paidIn = five.ownContributions + five.employerContributions + five.stateIncentives
        assertThat(zero.projectedValue).isEqualByComparingTo(paidIn)
        assertThat(five.projectedValue).isGreaterThan(paidIn)
        assertThat(five.employerContributions).isEqualByComparingTo("120000.00")
    }

    @Test
    fun `a DIP gets no state contribution in its projection`() {
        val p = RetirementProjection.project(
            dip,
            listOf(flat),
            ProjectionRequest(BigDecimal("4000"), BigDecimal.ZERO, 5),
        )
            .single()
        assertThat(p.stateIncentives).isEqualByComparingTo("0.00")
    }

    @Test
    fun `an out-of-range horizon or a non-positive contribution is refused`() {
        assertThatThrownBy {
            RetirementProjection.project(dps, listOf(flat), ProjectionRequest(BigDecimal.ONE, BigDecimal.ZERO, 61))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            RetirementProjection.project(dps, listOf(flat), ProjectionRequest(BigDecimal.ZERO, BigDecimal.ZERO, 10))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the disclaimer says illustrative and not a forecast`() {
        assertThat(RetirementProjection.DISCLAIMER).contains("Illustrative").contains("not a forecast")
    }
}
