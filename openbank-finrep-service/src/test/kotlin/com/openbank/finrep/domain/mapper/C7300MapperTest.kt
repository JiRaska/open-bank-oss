// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.application.port.out.RiskOutflowLine
import com.openbank.finrep.domain.model.CorepTemplate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** COREP C 73.00 from the risk engine's LCR outflows: components tie to the total, and every gap says why. */
class C7300MapperTest {

    private val asOf = LocalDate.parse("2026-09-30")

    private fun line(key: String, amount: String, factor: String) = BigDecimal(amount).let { a ->
        val f = BigDecimal(factor)
        RiskOutflowLine(key, a, f, a.multiply(f))
    }

    private val lines = listOf(
        line(C7300Mapper.STABLE, "2000", "0.05"),
        line(C7300Mapper.LESS_STABLE, "3000", "0.10"),
        line(C7300Mapper.OPERATIONAL, "400", "0.25"),
        line(C7300Mapper.OTHER, "50", "1"),
    )

    // 100 + 300 + 100 + 50
    private fun result(
        lines: List<RiskOutflowLine> = this.lines,
        total: String? = "550",
        currencies: Int = 1,
        unclassified: Int = 0,
        parameterSet: String = RiskEngineFigures.EU_LIQUIDITY_PARAMETER_SET,
        currency: String? = "CZK",
        totalNotStated: String? = null,
    ) = RiskLiquidityResult(
        "run-7", asOf, parameterSet, "2", currency, emptyList(),
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, currencies, unclassified,
        outflows = lines, totalOutflows = total?.let(::BigDecimal),
        totalNotStated = totalNotStated,
    )

    private fun CorepTemplate.at(row: String, col: String) = cells.single { it.rowRef == row && it.colRef == col }

    @Test
    fun `the total ties while portfolio estimates and generic liabilities are classification gaps`() {
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result()), asOf)

        assertThat(t.templateId).isEqualTo("C_73.00")
        assertThat(t.at("r0010", "c0010").value).isEqualByComparingTo("5450")
        assertThat(t.at("r0010", "c0060").value).isEqualByComparingTo("550")
        listOf("c0010", "c0060").forEach { col ->
            assertThat(t.at("r0010", col).isDataGap).isFalse()
            listOf("r0030", "r0080", "r0110", "r0120").forEach { row ->
                assertThat(t.at(row, col).value).isEqualByComparingTo("0")
                assertThat(t.at(row, col).isDataGap).isTrue()
                assertThat(t.at(row, col).gapReason).contains("configured deposit shares")
            }
            assertThat(t.at("r0885", col).isDataGap).isTrue()
            assertThat(t.at("r0885", col).gapReason).contains("generic GL liabilities")
        }
    }

    @Test
    fun `the total row carries every component, so it equals the engine's own total`() {
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        assertThat(t.at("r0010", "c0060").value).isEqualByComparingTo(result().totalOutflows)
    }

    @Test
    fun `components that do not tie to the engine's total fail the render`() {
        assertThatThrownBy { C7300Mapper.map(RiskLiquidityLookup.found(result(lines = lines.dropLast(1))), asOf) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("total outflows are 550")
    }

    @Test
    fun `per-line cent rounding is tolerated but a cent beyond it is not`() {
        val rounded = listOf(line(C7300Mapper.STABLE, "0.10", "0.05"), line(C7300Mapper.STABLE, "0.10", "0.05"))
            .map { it.copy(weighted = BigDecimal("0.01")) }
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(lines = rounded, total = "0.01")), asOf)
        assertThat(t.at("r0010", "c0060").value).isEqualByComparingTo("0.02")
        assertThatThrownBy {
            C7300Mapper.map(RiskLiquidityLookup.found(result(lines = rounded, total = "0.04")), asOf)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `an outflow the mapper does not know makes the total a gap naming it, not an exception`() {
        val extra = lines + line("lcr-committed-facility", "10", "0.05")
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(lines = extra, total = "550.5")), asOf)
        listOf("c0010", "c0060").forEach { col ->
            assertThat(t.at("r0010", col).isDataGap).describedAs(col).isTrue()
            assertThat(t.at("r0010", col).gapReason)
                .contains("engine reports component 'lcr-committed-facility' this template does not map yet")
        }
        assertThat(t.at("r0930", "c0060").isDataGap).isFalse()
    }

    @Test
    fun `an unknown outflow still counts toward the tie, so a mismatched engine total still fails`() {
        val extra = lines + line("lcr-committed-facility", "10", "0.05")
        assertThatThrownBy { C7300Mapper.map(RiskLiquidityLookup.found(result(lines = extra, total = "550")), asOf) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a CNB lombard maps to secured funding with a central bank at 0 percent and the total ties with it`() {
        val withLombard = lines + line(C7300Mapper.CB_SECURED, "700", "0")
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(lines = withLombard, total = "550")), asOf)
        assertThat(t.at("r0930", "c0010").value).isEqualByComparingTo("700")
        assertThat(t.at("r0930", "c0060").value).isEqualByComparingTo("0")
        assertThat(t.at("r0930", "c0060").isDataGap).isFalse()
        assertThat(t.at("r0010", "c0010").value).isEqualByComparingTo("6150")
        assertThat(t.at("r0010", "c0060").value).isEqualByComparingTo("550")
        assertThat(t.at("r0010", "c0060").isDataGap).isFalse()
    }

    @Test
    fun `a CNB lombard at a non-zero rate is not the 2015-61 rate, so its outflow is a gap`() {
        val withLombard = lines + line(C7300Mapper.CB_SECURED, "700", "0.25")
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(lines = withLombard, total = "725")), asOf)
        assertThat(t.at("r0930", "c0060").isDataGap).isTrue()
        assertThat(t.at("r0010", "c0060").isDataGap).isTrue()
    }

    @Test
    fun `higher-outflow retail and non-operational deposits are always gaps with a reason`() {
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result()), asOf)
        assertThat(t.at("r0050", "c0060").isDataGap).isTrue()
        assertThat(t.at("r0050", "c0060").gapReason).isEqualTo(C7300Mapper.HIGHER_OUTFLOW_REASON)
        assertThat(t.at("r0210", "c0010").isDataGap).isTrue()
        assertThat(t.at("r0210", "c0010").gapReason).isEqualTo(C7300Mapper.NON_OPERATIONAL_REASON)
    }

    @Test
    fun `a factor that is not the 2015-61 rate makes the weighted total a gap`() {
        val derogated = listOf(line(C7300Mapper.STABLE, "2000", "0.03")) + lines.drop(1)
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(lines = derogated, total = "510")), asOf)
        assertThat(t.at("r0010", "c0060").isDataGap).isTrue()
        assertThat(t.at("r0010", "c0060").gapReason).contains("0.03", "'eu-2015-61-crr2'").doesNotContain("d238")
        assertThat(t.at("r0010", "c0010").isDataGap).isFalse()
        assertThat(t.at("r0930", "c0060").isDataGap).isFalse()
    }

    @Test
    fun `a multi-currency book is reported from the engine's combined CZK view`() {
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(currencies = 2)), asOf)
        assertThat(t.at("r0010", "c0060").isDataGap).isFalse()
        assertThat(t.at("r0010", "c0060").value).isEqualByComparingTo("550")
    }

    @Test
    fun `unavailable, no combined view, a non-CZK total and unclassified results make every row a gap`() {
        val cases = listOf(
            RiskLiquidityLookup.unavailable("read disabled") to "read disabled",
            RiskLiquidityLookup.found(
                result(
                    currencies = 2,
                    total = null,
                    currency = null,
                    totalNotStated = "no ČNB fixing in effect on 2026-09-30 for EUR: no partial total",
                ),
            ) to "no ČNB fixing in effect on 2026-09-30 for EUR",
            RiskLiquidityLookup.found(result(currency = "EUR")) to "not the reporting currency CZK",
            RiskLiquidityLookup.found(result(unclassified = 3)) to "3 balance(s) are unclassified",
            RiskLiquidityLookup.found(result(lines = emptyList(), currencies = 0, total = null)) to "empty book",
        )
        cases.forEach { (lookup, reason) ->
            val t = C7300Mapper.map(lookup, asOf)
            assertThat(t.cells).allSatisfy { c ->
                assertThat(c.isDataGap).isTrue()
                assertThat(c.gapReason).contains(reason)
            }
        }
    }

    @Test
    fun `a run on a non-EU parameter set blanks every 2015-61 outflow cell and names the set`() {
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result(parameterSet = "bcbs-d238-d295")), asOf)
        listOf("r0010", "r0930").forEach { row ->
            assertThat(t.at(row, "c0060").isDataGap).isTrue()
            assertThat(t.at(row, "c0060").gapReason).contains("'bcbs-d238-d295'", "run-7", "eu-2015-61-crr2")
            assertThat(t.at(row, "c0010").isDataGap).isFalse()
        }
    }

    @Test
    fun `an empty book is not reported as multi-currency`() {
        val t = C7300Mapper.map(
            RiskLiquidityLookup.found(result(lines = emptyList(), currencies = 0, total = null)),
            asOf,
        )
        assertThat(t.at("r0010", "c0010").gapReason).contains("empty book").doesNotContain("multi-currency")
    }

    @Test
    fun `the template carries the source run's id and provenance, and none when there is no run`() {
        val t = C7300Mapper.map(RiskLiquidityLookup.found(result().copy(provenance = "synthetic")), asOf)
        assertThat(t.sourceRunId).isEqualTo("run-7")
        assertThat(t.provenance).isEqualTo("synthetic")
        val gap = C7300Mapper.map(RiskLiquidityLookup.unavailable("no run"), asOf)
        assertThat(gap.sourceRunId).isNull()
        assertThat(gap.provenance).isNull()
    }
}
