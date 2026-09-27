// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskInflowLine
import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.domain.model.CorepTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** COREP C 74.00 from the risk engine's LCR inflows: components tie to the total, the cap maps both ways. */
class C7400MapperTest {

    private val asOf = LocalDate.parse("2026-09-30")

    private fun line(key: String, amount: String, factor: String) = BigDecimal(amount).let { a ->
        val f = BigDecimal(factor)
        RiskInflowLine(key, a, f, a.multiply(f))
    }

    private val lines = listOf(
        line(C7400Mapper.NON_FINANCIAL, "600", "0.50"),
        line(C7400Mapper.FINANCIAL, "80", "1.00"),
        line(C7400Mapper.OPERATIONAL, "200", "0"),
    )

    // weighted: 300 + 80 + 0 = 380; outflows 400 -> cap 300 -> binding, capped 300
    private fun result(
        lines: List<RiskInflowLine> = this.lines,
        total: String? = "380",
        outflows: String = "400",
        cap: String? = "300",
        capped: String? = "300",
        currencies: Int = 1,
        unclassified: Int = 0,
    ) = RiskLiquidityResult(
        "run-7", asOf, "bcbs-d238-d295", "2", "CZK", emptyList(),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, currencies, unclassified,
        totalOutflows = BigDecimal(outflows),
        inflows = lines,
        totalInflows = total?.let(::BigDecimal),
        inflowCap = cap?.let(::BigDecimal),
        cappedInflows = capped?.let(::BigDecimal),
        inflowCapBinding = true,
    )

    private fun CorepTemplate.at(row: String, col: String) = cells.single { it.rowRef == row && it.colRef == col }

    @Test
    fun `component rows carry amount and inflow, and the total ties to the engine's uncapped total`() {
        val t = C7400Mapper.map(RiskLiquidityLookup.found(result()), asOf)

        assertThat(t.templateId).isEqualTo("C_74.00")
        assertThat(t.at("r0010", "c0010").value).isEqualByComparingTo("880")
        assertThat(t.at("r0010", "c0140").value).isEqualByComparingTo("380")
        assertThat(t.at("r0030", "c0140").value).isEqualByComparingTo("300")
        assertThat(t.at("r0160", "c0010").value).isEqualByComparingTo("280")
        assertThat(t.at("r0160", "c0140").value).isEqualByComparingTo("80")
        assertThat(t.at("r0170", "c0140").value).isEqualByComparingTo("0")
        assertThat(t.at("r0170", "c0010").value).isEqualByComparingTo("200")
        assertThat(t.at("r0180", "c0140").value).isEqualByComparingTo("80")
        listOf("r0010", "r0030", "r0160", "r0170", "r0180").forEach { r ->
            assertThat(t.at(r, "c0010").isDataGap).isFalse()
            assertThat(t.at(r, "c0140").isDataGap).isFalse()
        }
    }

    @Test
    fun `no row is emitted for the capped inflows, which belong to C 76_00`() {
        val t = C7400Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        assertThat(t.at("r0010", "c0140").value).isEqualByComparingTo("380")
        assertThat(t.cells.map { it.rowRef }.toSet())
            .containsExactlyInAnyOrder("r0010", "r0030", "r0160", "r0170", "r0180", "r0200", "r0260")
    }

    @Test
    fun `an engine that reports no cap still renders the uncapped rows`() {
        val t = C7400Mapper.map(RiskLiquidityLookup.found(result(cap = null, capped = null)), asOf)
        assertThat(t.at("r0010", "c0140").isDataGap).isFalse()
    }

    @Test
    fun `a capped figure that is not min of uncapped and cap fails the render`() {
        assertThatThrownBy { C7400Mapper.map(RiskLiquidityLookup.found(result(capped = "380")), asOf) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("capped inflows are 380")
    }

    @Test
    fun `components that do not tie to the engine's total fail the render`() {
        assertThatThrownBy { C7400Mapper.map(RiskLiquidityLookup.found(result(lines = lines.drop(1))), asOf) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("total inflows are 380")
    }

    @Test
    fun `per-line cent rounding within tolerance still ties`() {
        val t = C7400Mapper.map(RiskLiquidityLookup.found(result(total = "380.01")), asOf)
        assertThat(t.at("r0010", "c0140").value).isEqualByComparingTo("380")
    }

    @Test
    fun `an inflow component the mapper does not know fails the render instead of disappearing`() {
        val extra = lines + line("lcr-reverse-repo-inflow", "10", "1")
        assertThatThrownBy {
            C7400Mapper.map(RiskLiquidityLookup.found(result(lines = extra, total = "390")), asOf)
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("lcr-reverse-repo-inflow")
    }

    @Test
    fun `a component at a rate other than 2015-61 is a gap in its inflow column and in the totals`() {
        val off = listOf(line(C7400Mapper.NON_FINANCIAL, "600", "0.40"), lines[1], lines[2])
        val t = C7400Mapper.map(
            RiskLiquidityLookup.found(result(lines = off, total = "320", cap = "300", capped = "300")),
            asOf,
        )
        assertThat(t.at("r0030", "c0140").isDataGap).isTrue()
        assertThat(t.at("r0010", "c0140").isDataGap).isTrue()
        assertThat(t.at("r0030", "c0010").isDataGap).isFalse()
        assertThat(t.at("r0180", "c0140").isDataGap).isFalse()
    }

    @Test
    fun `unmodelled rows are always gaps with their reason`() {
        val t = C7400Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        assertThat(t.at("r0260", "c0140").gapReason).isEqualTo(C7400Mapper.SECURED_REASON)
        assertThat(t.at("r0200", "c0010").gapReason).isEqualTo(C7400Mapper.CENTRAL_BANK_REASON)
    }

    @Test
    fun `every cell is a gap when the book is multi-currency, unclassified or unavailable`() {
        listOf(
            RiskLiquidityLookup.found(result(currencies = 2, total = null)),
            RiskLiquidityLookup.found(result(unclassified = 3)),
            RiskLiquidityLookup.unavailable("no snapshot"),
        ).forEach { lookup ->
            val t = C7400Mapper.map(lookup, asOf)
            assertThat(t.cells).allSatisfy { assertThat(it.isDataGap).isTrue() }
        }
    }

    @Test
    fun `every unverified row and column says so on the wire, and r0010 does not`() {
        val t = C7400Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        t.cells.filter { it.rowRef in C7400Mapper.UNVERIFIED_ROWS }
            .forEach { assertThat(it.label).contains("[row code UNVERIFIED]") }
        t.cells.filter { it.colRef in C7400Mapper.UNVERIFIED_COLUMNS }
            .forEach { assertThat(it.label).contains("[column code UNVERIFIED]") }
        assertThat(t.at("r0010", "c0010").label).isEqualTo("TOTAL INFLOWS")
    }
}
