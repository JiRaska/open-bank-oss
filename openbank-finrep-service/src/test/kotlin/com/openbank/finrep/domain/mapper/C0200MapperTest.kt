// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskCapitalLookup
import com.openbank.finrep.application.port.out.RiskCapitalResult
import com.openbank.finrep.application.port.out.RiskExposureClass
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** ADR-0313 D6: C 02.00 from the risk engine's Pillar 1 result, framework 4.0 CA2 layout. */
class C0200MapperTest {

    private val asOf = LocalDate.parse("2026-09-30")

    private fun result(
        classes: List<RiskExposureClass> = listOf(
            RiskExposureClass("sovereign-and-central-bank", BigDecimal("10000000.00"), BigDecimal("0.00")),
            RiskExposureClass("bank", BigDecimal("1500.00"), BigDecimal("2250.00")),
            RiskExposureClass("retail", BigDecimal("6093596.08"), BigDecimal("6093596.08")),
            RiskExposureClass("cash", BigDecimal("300.00"), BigDecimal("0.00")),
            RiskExposureClass("other-asset", BigDecimal("50.00"), BigDecimal("50.00")),
        ),
        total: String? = "6095896.08",
        currencies: Int = 1,
        unclassified: Int = 0,
    ) = RiskCapitalLookup.found(
        RiskCapitalResult(
            "run-1", asOf, "bcbs-d424-sa", "1", "CZK", classes,
            total?.let(
                ::BigDecimal,
            ),
            currencies, unclassified,
        ),
    )

    private fun cells(lookup: RiskCapitalLookup) = C0200Mapper.map(lookup, asOf).cells.associateBy { it.rowRef }

    @Test
    fun `a tied single-currency result fills the SA rows from the engine's classes`() {
        val c = cells(result())
        assertThat(c.getValue("r0070").value).isEqualByComparingTo("0.00")
        assertThat(c.getValue("r0120").value).isEqualByComparingTo("2250.00")
        assertThat(c.getValue("r0140").value).isEqualByComparingTo("6093596.08")
        // cash and other-asset both land on 0211 Other items.
        assertThat(c.getValue("r0211").value).isEqualByComparingTo("50.00")
        listOf("r0040", "r0050", "r0060").forEach {
            assertThat(c.getValue(it).value).isEqualByComparingTo("6095896.08")
            assertThat(c.getValue(it).isDataGap).isFalse()
        }
        assertThat(c.getValue("r0125").isDataGap).isFalse()
        assertThat(c.getValue("r0125").value).isEqualByComparingTo("0")
        assertThat(c.values.map { it.colRef }).containsOnly("c0010")
    }

    @Test
    fun `TREA, the unmodelled classes and the uncomputed risks are gaps, never zeros`() {
        val c = cells(result())
        assertThat(c.getValue("r0010").isDataGap).isTrue()
        assertThat(c.getValue("r0010").gapReason).contains("Article 92(3)")
        listOf("r0080", "r0090", "r0100", "r0110", "r0131", "r0150", "r0171", "r0180", "r0190", "r0200", "r0210")
            .forEach { assertThat(c.getValue(it).isDataGap).describedAs(it).isTrue() }
        listOf("r0520", "r0590", "r0640").forEach { assertThat(c.getValue(it).isDataGap).describedAs(it).isTrue() }
    }

    @Test
    fun `no usable source makes every credit-risk row a gap with the source's reason`() {
        val c = cells(RiskCapitalLookup.unavailable("No TIED_OUT risk-engine snapshot exists at the report date."))
        listOf("r0010", "r0040", "r0050", "r0060", "r0070", "r0120", "r0140", "r0211").forEach {
            assertThat(c.getValue(it).isDataGap).describedAs(it).isTrue()
            assertThat(c.getValue(it).gapReason).contains("TIED_OUT")
        }
    }

    @Test
    fun `unclassified balances or a multi-currency book make the credit-risk rows gaps`() {
        assertThat(cells(result(unclassified = 2)).getValue("r0040").gapReason).contains("unclassified")
        assertThat(cells(result(currencies = 2, total = null)).getValue("r0040").gapReason).contains("multi-currency")
    }

    @Test
    fun `an engine class with no row, or classes not summing to the total, fails the render`() {
        assertThatThrownBy {
            cells(result(classes = listOf(RiskExposureClass("mortgage", BigDecimal.ONE, BigDecimal.ONE)), total = "1"))
        }
            .hasMessageContaining("mortgage")
        assertThatThrownBy { cells(result(total = "1.00")) }.hasMessageContaining("total RWA")
    }
}
