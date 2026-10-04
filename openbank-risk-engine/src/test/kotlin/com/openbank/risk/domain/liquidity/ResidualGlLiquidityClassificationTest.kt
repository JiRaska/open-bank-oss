// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.limits.LimitInputs
import com.openbank.risk.domain.limits.MetricInput
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * #11107: GL 1100, 1990, 1991, 1995 and 2200 were not classified for liquidity, so every sandbox run
 * listed them as unclassified and the LCR and NSFR limits were NOT_EVALUABLE. Each is now a
 * POLICY CHOICE in the SHIPPED classification (application.yaml), conservative on both sides.
 */
class ResidualGlLiquidityClassificationTest {

    private val asOf = Fixtures.AS_OF
    private val shipped = LiquidityTestParameters.shipped()
    private val eurFixing = mapOf("EUR" to FxRateUsed("EUR", BigDecimal("25.00"), asOf, "CNB"))

    private fun gl(code: String, type: String, amount: String, ccy: String = "CZK") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private fun deposits(amount: String, ccy: String = "CZK") =
        Position(PositionKind.SUB_LEDGER, "2100", "LIABILITY", ccy, Fixtures.ALICE, BigDecimal(amount))

    /**
     * The sandbox book of snapshot run 01a1058c (as of 2026-10-04), GL by GL. The two MM placements
     * on 1500 are carried at GL level (no contract data), which only removes a possible inflow.
     */
    private val sandbox = listOf(
        gl("1001", "ASSET", "-1998611.11"),
        gl("1100", "ASSET", "22415076.00"),
        gl("1300", "ASSET", "0"),
        gl("1500", "ASSET", "2000000.00"),
        gl("1520", "ASSET", "194.44"),
        gl("1990", "ASSET", "-132.00"),
        gl("1991", "ASSET", "5.19", "EUR"),
        gl("1995", "ASSET", "-126.97"),
        deposits("-5919044.47"),
        deposits("-5.19", "EUR"),
        gl("2200", "LIABILITY", "-731.00"),
        gl("4003", "INCOME", "-100.50"),
        gl("4010", "EXPENSE", "4931.97"),
        gl("4200", "INCOME", "-1583.33"),
        gl("5900", "INCOME", "126.97"),
        gl("6000", "EQUITY", "-10000000.00"),
        gl("6010", "EQUITY", "-2000000.00"),
        gl("6020", "EQUITY", "-500000.00"),
        gl("6030", "EQUITY", "-1000000.00"),
        gl("6050", "EQUITY", "-1000000.00"),
        gl("6060", "EQUITY", "-2000000.00"),
    )

    @Test
    fun `each residual GL has its conservative class in the shipped classification`() {
        val m = shipped.classification.glAccounts
        assertThat(m["1100"]).isEqualTo(GlClass.TECHNICAL_OR_CLEARING)
        assertThat(m["1990"]).isEqualTo(GlClass.TECHNICAL_OR_CLEARING)
        assertThat(m["1991"]).isEqualTo(GlClass.TECHNICAL_OR_CLEARING)
        assertThat(m["1995"]).isEqualTo(GlClass.TECHNICAL_OR_CLEARING)
        assertThat(m["2200"]).isEqualTo(GlClass.OTHER_LIABILITY)
        assertThat(GlClass.TECHNICAL_OR_CLEARING.isHqla).isFalse()
    }

    @Test
    fun `the classification change bumped both parameter set versions`() {
        assertThat(LiquidityTestParameters.shipped(LiquidityTestParameters.EU_SET).version).isEqualTo("4")
        assertThat(LiquidityTestParameters.shipped(LiquidityTestParameters.BCBS_SET).version).isEqualTo("5")
    }

    @Test
    fun `a debit technical balance is never HQLA nor an inflow, and carries 100 percent RSF`() {
        val r = Liquidity.compute(listOf(gl("1100", "ASSET", "1000.00")), emptyList(), asOf, shipped).total!!
        assertThat(r.lcr.hqla.lines).isEmpty()
        assertThat(r.lcr.inflows).isEmpty()
        assertThat(r.lcr.outflows).isEmpty()
        assertThat(r.nsfr.rsf.single().weighted).isEqualByComparingTo("1000.00")
        assertThat(r.nsfr.asf).isEmpty()
    }

    @Test
    fun `a credit technical balance is a 100 percent outflow with 0 percent ASF, never a negative RSF`() {
        val r = Liquidity.compute(listOf(gl("1990", "ASSET", "-132.00")), emptyList(), asOf, shipped).total!!
        assertThat(r.nsfr.rsf).describedAs("other-asset would have produced RSF -132").isEmpty()
        assertThat(r.lcr.outflows.single().weighted).isEqualByComparingTo("132.00")
        assertThat(r.nsfr.asf.single().weighted).isEqualByComparingTo("0")
    }

    @Test
    fun `withholding tax payable is a 100 percent outflow with 0 percent ASF`() {
        val r = Liquidity.compute(listOf(gl("2200", "LIABILITY", "-731.00")), emptyList(), asOf, shipped).total!!
        assertThat(r.lcr.outflows.single().weighted).isEqualByComparingTo("731.00")
        assertThat(r.nsfr.asf.single().weighted).isEqualByComparingTo("0")
    }

    @Test
    fun `on the sandbox book nothing is unclassified and LCR and NSFR are evaluable`() {
        val r = Liquidity.compute(sandbox, emptyList(), asOf, shipped, eurFixing)
        assertThat(r.unclassified).isEmpty()

        val lcr = LimitInputs.lcr(r)
        val nsfr = LimitInputs.nsfr(r)
        assertThat(lcr).isInstanceOf(MetricInput.Measured::class.java)
        assertThat(nsfr).isInstanceOf(MetricInput.Measured::class.java)

        // No HQLA on the book (no 1510, nostro overdrawn): LCR 0. Outflows: 10% × 5,919,044.47 retail,
        // + 731 (2200) + 132 (1990) + 126.97 (1995) + 10% × 5.19 EUR × 25.
        assertThat(r.total!!.lcr.totalOutflows).isEqualByComparingTo("592907.392")
        assertThat((lcr as MetricInput.Measured).value).isEqualByComparingTo("0")
        // ASF: 14.5m capital + 90% × 5,919,044.47 + 90% × 5.19 × 25 = 19,827,256.798.
        // RSF: 22,415,076 (1100) + 2,000,000 (1500) + 194.44 (1520) + 129.75 (1991) − 999,305.555 (1001).
        assertThat(r.total!!.nsfr.totalAsf).isEqualByComparingTo("19827256.798")
        assertThat(r.total!!.nsfr.totalRsf).isEqualByComparingTo("23416094.635")
        assertThat((nsfr as MetricInput.Measured).value.toDouble()).isCloseTo(0.846736, within(0.000001))
    }

    @Test
    fun `sabotage - with any one of the five unmapped again, both limits fall back to NOT_EVALUABLE`() {
        for (code in listOf("1100", "1990", "1991", "1995", "2200")) {
            val params =
                LiquidityTestParameters.withClassification(glAccounts = shipped.classification.glAccounts - code)
            val r = Liquidity.compute(sandbox, emptyList(), asOf, params, eurFixing)
            assertThat(r.unclassified.map { it.glAccountCode }).describedAs(code).containsExactly(code)
            assertThat(LimitInputs.lcr(r)).describedAs(code).isInstanceOf(MetricInput.Gap::class.java)
            assertThat(LimitInputs.nsfr(r)).describedAs(code).isInstanceOf(MetricInput.Gap::class.java)
        }
    }
}
