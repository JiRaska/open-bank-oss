// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskHqlaLine
import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.domain.model.CorepTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** COREP C 72.00 from the risk engine's LCR liquid assets: totals tie, and every gap says why. */
class C7200MapperTest {

    private val asOf = LocalDate.parse("2026-09-30")

    private fun line(level: String, mv: String, haircut: String) = BigDecimal(mv).let { m ->
        val h = BigDecimal(haircut)
        RiskHqlaLine(level, m, h, m.multiply(BigDecimal.ONE.subtract(h)))
    }

    private val lines = listOf(
        line("L1", "1000", "0"),
        line("L1", "250", "0"),
        line("L2A", "200", "0.15"),
        line("L2B", "100", "0.25"),
        line("L2B", "60", "0.50"),
    )

    private fun result(
        lines: List<RiskHqlaLine> = this.lines,
        level1: String? = "1250",
        level2a: String? = "170",
        level2b: String? = "105",
        currencies: Int = 1,
        unclassified: Int = 0,
    ) = RiskLiquidityResult(
        "run-7", asOf, "bcbs-d238-d295", "2", "CZK", lines,
        level1?.let(::BigDecimal), level2a?.let(::BigDecimal), level2b?.let(::BigDecimal), currencies, unclassified,
    )

    private fun CorepTemplate.at(row: String, col: String) = cells.single { it.rowRef == row && it.colRef == col }

    @Test
    fun `level rows carry market value and Article 9 value, and the totals tie`() {
        val t = C7200Mapper.map(RiskLiquidityLookup.found(result()), asOf)

        assertThat(t.templateId).isEqualTo("C_72.00")
        assertThat(t.at("r0020", "c0010").value).isEqualByComparingTo("1250")
        assertThat(t.at("r0020", "c0040").value).isEqualByComparingTo("1250")
        assertThat(t.at("r0030", "c0040").value).isEqualByComparingTo("1250")
        assertThat(t.at("r0240", "c0010").value).isEqualByComparingTo("200")
        assertThat(t.at("r0240", "c0040").value).isEqualByComparingTo("170")
        assertThat(t.at("r0310", "c0010").value).isEqualByComparingTo("160")
        assertThat(t.at("r0310", "c0040").value).isEqualByComparingTo("105")
        assertThat(t.at("r0230", "c0040").value).isEqualByComparingTo("275")
        assertThat(t.at("r0010", "c0010").value).isEqualByComparingTo("1610")
        assertThat(t.at("r0010", "c0040").value).isEqualByComparingTo("1525")
        // Totals tie in both columns: r0010 = r0020 + r0230, r0230 = r0240 + r0310.
        listOf("c0010", "c0040").forEach { col ->
            assertThat(t.at("r0010", col).value)
                .isEqualByComparingTo(t.at("r0020", col).value.add(t.at("r0230", col).value))
            assertThat(t.at("r0230", col).value)
                .isEqualByComparingTo(t.at("r0240", col).value.add(t.at("r0310", col).value))
        }
        val valueRows = listOf("r0010", "r0020", "r0030", "r0230", "r0240", "r0310")
        assertThat(t.cells.filter { it.rowRef in valueRows }).allMatch { !it.isDataGap && it.currency == "CZK" }
    }

    @Test
    fun `coins, banknotes and central bank reserves are a gap because the engine does not split them`() {
        val t = C7200Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        listOf("r0040", "r0050").forEach { row ->
            listOf("c0010", "c0040").forEach { col ->
                val c = t.at(row, col)
                assertThat(c.isDataGap).isTrue()
                assertThat(c.value).isEqualByComparingTo("0")
                assertThat(c.gapReason).isEqualTo(C7200Mapper.NOT_SPLIT_REASON)
            }
        }
    }

    @Test
    fun `rows whose code is unverified say so on the wire`() {
        val t = C7200Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        assertThat(t.cells.filter { it.rowRef in C7200Mapper.UNVERIFIED_ROWS }).isNotEmpty
            .allMatch { it.label.endsWith("[row code UNVERIFIED]") }
        assertThat(t.cells.filter { it.rowRef !in C7200Mapper.UNVERIFIED_ROWS })
            .noneMatch { it.label.contains("UNVERIFIED") }
    }

    @Test
    fun `an unavailable source makes every cell a gap with the source's reason`() {
        val t = C7200Mapper.map(RiskLiquidityLookup.unavailable("No TIED_OUT risk-engine snapshot"), asOf)
        assertThat(t.cells).hasSize(16).allMatch { it.isDataGap && it.gapReason == "No TIED_OUT risk-engine snapshot" }
    }

    @Test
    fun `unclassified balances make every value row a gap naming the snapshot`() {
        val t = C7200Mapper.map(RiskLiquidityLookup.found(result(unclassified = 2)), asOf)
        assertThat(t.cells).allMatch { it.isDataGap && it.value.signum() == 0 }
        assertThat(t.at("r0010", "c0040").gapReason).contains("2 balance(s) are unclassified", "run-7")
    }

    @Test
    fun `a multi-currency book is a gap, never a sum across currencies`() {
        val t = C7200Mapper.map(
            RiskLiquidityLookup.found(result(level1 = null, level2a = null, level2b = null, currencies = 2)),
            asOf,
        )
        assertThat(t.at("r0020", "c0010").gapReason).contains("multi-currency")
        assertThat(t.cells).allMatch { it.isDataGap }
    }

    @Test
    fun `a haircut that is not a 2015-61 standard haircut blanks the Article 9 value, not the market value`() {
        val odd = listOf(line("L1", "1000", "0"), line("L2A", "200", "0.20"))
        val t = C7200Mapper.map(
            RiskLiquidityLookup.found(result(lines = odd, level1 = "1000", level2a = "160", level2b = "0")),
            asOf,
        )
        assertThat(t.at("r0240", "c0040").isDataGap).isTrue()
        assertThat(t.at("r0240", "c0040").gapReason).contains("L2A haircut of 0.20")
        assertThat(t.at("r0010", "c0040").isDataGap).isTrue()
        assertThat(t.at("r0240", "c0010").isDataGap).isFalse()
        assertThat(t.at("r0020", "c0040").isDataGap).isFalse()
    }

    @Test
    fun `an empty liquid-asset book is a stated zero only because the engine tied out with none`() {
        val t = C7200Mapper.map(
            RiskLiquidityLookup.found(result(lines = emptyList(), level1 = "0", level2a = "0", level2b = "0")),
            asOf,
        )
        assertThat(t.at("r0010", "c0010").isDataGap).isFalse()
        assertThat(t.at("r0010", "c0010").value).isEqualByComparingTo("0")
    }

    @Test
    fun `lines that disagree with the engine's own level total fail the render`() {
        assertThatThrownBy { C7200Mapper.map(RiskLiquidityLookup.found(result(level1 = "1249")), asOf) }
            .hasMessageContaining("L1 lines sum to 1250")
    }

    @Test
    fun `an unknown HQLA level fails the render instead of being dropped`() {
        assertThatThrownBy {
            C7200Mapper.map(RiskLiquidityLookup.found(result(lines = lines + line("L3", "1", "0"))), asOf)
        }.hasMessageContaining("'L3'")
    }
}
