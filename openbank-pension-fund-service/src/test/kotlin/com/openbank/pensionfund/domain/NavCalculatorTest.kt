// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain

import com.openbank.pensionfund.domain.model.FourEyesViolationException
import com.openbank.pensionfund.domain.model.NavCalculator
import com.openbank.pensionfund.domain.model.NavInput
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import com.openbank.pensionfund.domain.model.PricedPosition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class NavCalculatorTest {

    private fun input(units: String, days: Int = 1) = NavInput(
        positions = listOf(
            PricedPosition("CZ0001001796", BigDecimal("1000"), BigDecimal("98.765")),
            PricedPosition("IE00B4L5Y983", BigDecimal("250"), BigDecimal("401.20")),
        ),
        cash = BigDecimal("50000.00"),
        otherLiabilities = BigDecimal("1200.00"),
        unitsOutstanding = BigDecimal(units),
        managementFeeRate = BigDecimal("0.0080"),
        accrualDays = days,
        launchNavPerUnit = BigDecimal("1"),
    )

    @Test
    fun `nav is positions plus cash minus accrued fee and liabilities, per unit`() {
        val figures = NavCalculator.calculate(input("200000"))
        // 98765.00 + 100300.00 + 50000.00
        assertThat(figures.grossAssets).isEqualByComparingTo("249065.00")
        // 249065.00 * 0.008 / 365 = 5.4589... -> 5.46 (HALF_EVEN, money scale)
        assertThat(figures.accruedManagementFee).isEqualTo(BigDecimal("5.46"))
        assertThat(figures.netAssets).isEqualTo(BigDecimal("247859.54"))
        assertThat(figures.navPerUnit).isEqualTo(BigDecimal("1.239298"))
        assertThat(figures.navPerUnit.scale()).isEqualTo(6)
    }

    @Test
    fun `the fee accrues for every day since the previous valuation`() {
        val oneDay = NavCalculator.calculate(input("200000", days = 1))
        val weekend = NavCalculator.calculate(input("200000", days = 3))
        assertThat(weekend.accruedManagementFee).isEqualTo(BigDecimal("16.38"))
        assertThat(weekend.navPerUnit).isLessThan(oneDay.navPerUnit)
    }

    @Test
    fun `with no units outstanding the nav is the launch price`() {
        assertThat(NavCalculator.calculate(input("0")).navPerUnit).isEqualTo(BigDecimal("1.000000"))
    }

    @Test
    fun `negative net assets are refused rather than published`() {
        val broke = input("100").copy(otherLiabilities = BigDecimal("10000000"))
        assertThatThrownBy { NavCalculator.calculate(broke) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `publication needs a second person`() {
        val nav = NavRecord(
            id = UUID.randomUUID(),
            fundId = UUID.randomUUID(),
            valuationDate = LocalDate.parse("2026-10-09"),
            figures = NavCalculator.calculate(input("1000")),
            status = NavStatus.CALCULATED,
            calculatedBy = "maker",
            calculatedAt = Instant.parse("2026-10-09T16:00:00Z"),
        )
        assertThatThrownBy { nav.publish("maker", Instant.now()) }.isInstanceOf(FourEyesViolationException::class.java)
        val published = nav.publish("checker", Instant.parse("2026-10-09T17:00:00Z"))
        assertThat(published.status).isEqualTo(NavStatus.PUBLISHED)
        assertThat(published.approvedBy).isEqualTo("checker")
        assertThatThrownBy { published.publish("other", Instant.now()) }.isInstanceOf(IllegalStateException::class.java)
    }
}
