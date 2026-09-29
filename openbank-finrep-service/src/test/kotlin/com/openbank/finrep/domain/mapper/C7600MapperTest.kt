// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.domain.model.CorepTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** COREP C 76.00 from the risk engine's LCR: buffer, net outflows and ratio tie to the engine's own figures. */
class C7600MapperTest {

    private val asOf = LocalDate.parse("2026-09-30")

    // L1 1000 + L2A 300 + L2B 400 − 15 % cap adjustment 170.59 = buffer 1529.41.
    // Outflows 1000, inflows 900 -> cap 750 (75 %), capped 750, net 250; ratio 1529.41 / 250 = 6.117640.
    @Suppress("LongParameterList")
    private fun result(
        stock: String? = "1529.41",
        adj15: String? = "170.59",
        adj40: String? = "0",
        outflows: String = "1000",
        inflows: String = "900",
        cap: String = "750",
        capped: String = "750",
        net: String? = "250",
        ratio: String? = "6.117640",
        currencies: Int = 1,
        unclassified: Int = 0,
        parameterSet: String = RiskEngineFigures.EU_LIQUIDITY_PARAMETER_SET,
    ) = RiskLiquidityResult(
        "run-7", asOf, parameterSet, "2", "CZK", emptyList(),
        BigDecimal("1000"), BigDecimal("300"), BigDecimal("400"), currencies, unclassified,
        totalOutflows = BigDecimal(outflows),
        totalInflows = BigDecimal(inflows),
        inflowCap = BigDecimal(cap),
        cappedInflows = BigDecimal(capped),
        inflowCapBinding = true,
        level2bCapAdjustment = adj15?.let(::BigDecimal),
        level2CapAdjustment = adj40?.let(::BigDecimal),
        hqlaStock = stock?.let(::BigDecimal),
        netOutflows = net?.let(::BigDecimal),
        lcrRatio = ratio?.let(::BigDecimal),
    )

    private fun render(r: RiskLiquidityResult) = C7600Mapper.map(RiskLiquidityLookup.found(r), asOf)

    private fun CorepTemplate.at(row: String) = cells.single { it.rowRef == row && it.colRef == "c0010" }

    @Test
    fun `buffer, net outflows and ratio are the engine's figures and tie to each other`() {
        val t = render(result())

        assertThat(t.templateId).isEqualTo("C_76.00")
        assertThat(t.at("r0010").value).isEqualByComparingTo("1529.41")
        assertThat(t.at("r0020").value).isEqualByComparingTo("250")
        assertThat(t.at("r0030").value).isEqualByComparingTo("611.76")
        assertThat(t.at("r0030").currency).isEqualTo("%")
        assertThat(t.at("r0180").value).isEqualByComparingTo("1000")
        assertThat(t.at("r0210").value).isEqualByComparingTo("900")
        assertThat(t.at("r0240").value).isEqualByComparingTo("750")
        listOf("r0010", "r0020", "r0030", "r0180", "r0210", "r0240").forEach {
            assertThat(t.at(it).isDataGap).describedAs(it).isFalse()
        }
    }

    @Test
    fun `every row code is labelled unverified and no row outside the known set is emitted`() {
        val t = render(result())
        assertThat(t.cells.map { it.rowRef }).containsExactlyInAnyOrderElementsOf(C7600Mapper.UNVERIFIED_ROWS)
        assertThat(t.cells).allSatisfy { assertThat(it.label).endsWith("[row code UNVERIFIED]") }
    }

    @Test
    fun `the ratio is reported as a percentage rounded half-even to 2 dp`() {
        assertThat(C7600Mapper.percent(BigDecimal("1.001250"))).isEqualByComparingTo("100.12")
        assertThat(C7600Mapper.percent(BigDecimal("1.001350"))).isEqualByComparingTo("100.14")
        assertThat(C7600Mapper.percent(BigDecimal("6.117640"))).isEqualByComparingTo("611.76")
    }

    @Test
    fun `an engine ratio that is not buffer over net outflows fails the render`() {
        assertThatThrownBy { render(result(ratio = "6.200000")) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("LCR is 6.200000")
    }

    @Test
    fun `net outflows that are not outflows minus capped inflows fail the render`() {
        assertThatThrownBy { render(result(net = "260", ratio = "5.882346")) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("net outflows are 260")
    }

    @Test
    fun `a buffer that is not the level sums less the cap adjustments fails the render`() {
        assertThatThrownBy { render(result(adj15 = "100.00")) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("liquidity buffer is 1529.41")
    }

    @Test
    fun `a capped inflow that is not min of uncapped and 75 percent of outflows fails the render`() {
        assertThatThrownBy { render(result(capped = "740", net = "260", ratio = "5.882346")) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("capped inflows are 740")
        assertThatThrownBy { render(result(cap = "800", capped = "800", net = "200", ratio = "7.647050")) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("inflow cap is 800")
    }

    @Test
    fun `rounding within the shared tolerance still ties`() {
        val t = render(result(net = "250.01", ratio = "6.117395"))
        assertThat(t.at("r0020").value).isEqualByComparingTo("250.01")
    }

    @Test
    fun `a buffer the engine does not report after the caps is a gap, never a pre-cap sum`() {
        val t = render(result(adj15 = null, stock = null))
        assertThat(t.at("r0010").isDataGap).isTrue()
        assertThat(t.at("r0010").gapReason).isEqualTo(C7600Mapper.NO_CAPPED_BUFFER_REASON)
        assertThat(t.at("r0010").value).isEqualByComparingTo("0")
        assertThat(t.at("r0020").isDataGap).isFalse()
    }

    @Test
    fun `exempt and 90 percent cap rows are always gaps with their reasons`() {
        val t = render(result())
        listOf("r0190", "r0220").forEach { assertThat(t.at(it).gapReason).isEqualTo(C7600Mapper.EXEMPT_REASON) }
        listOf("r0200", "r0230").forEach { assertThat(t.at(it).gapReason).isEqualTo(C7600Mapper.HIGHER_CAP_REASON) }
    }

    @Test
    fun `non-positive net outflows leave the ratio a gap, not an infinite figure`() {
        val t = render(result(inflows = "0", capped = "0", outflows = "0", cap = "0", net = "0", ratio = null))
        assertThat(t.at("r0030").isDataGap).isTrue()
        assertThat(t.at("r0030").gapReason).isEqualTo(C7600Mapper.UNDEFINED_RATIO_REASON)
        assertThat(t.at("r0010").isDataGap).isFalse()
    }

    @Test
    fun `a non-EU parameter set makes every value a gap naming the set`() {
        val t = render(result(parameterSet = "cnb-local", cap = "750"))
        assertThat(t.cells).allSatisfy {
            assertThat(it.isDataGap).isTrue()
            assertThat(it.gapReason).contains("'cnb-local'")
        }
    }

    @Test
    fun `an empty book, a multi-currency book and unclassified balances are whole-template gaps`() {
        assertThat(render(result(currencies = 0)).cells).allSatisfy {
            assertThat(it.gapReason).contains("an empty book")
        }
        assertThat(render(result(currencies = 2)).cells).allSatisfy {
            assertThat(it.gapReason).contains("multi-currency")
        }
        assertThat(render(result(unclassified = 3)).cells).allSatisfy {
            assertThat(it.gapReason).contains("3 balance(s) are unclassified")
        }
    }

    @Test
    fun `an unavailable read is a gap on every row`() {
        val t = C7600Mapper.map(RiskLiquidityLookup.unavailable("read disabled"), asOf)
        assertThat(t.cells).hasSize(C7600Mapper.UNVERIFIED_ROWS.size)
        assertThat(t.cells).allSatisfy { assertThat(it.gapReason).isEqualTo("read disabled") }
    }
}
