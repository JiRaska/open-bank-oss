// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_FI_INFLOW
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_INFLOW_CAP
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_L1_HAIRCUT
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_L2A_HAIRCUT
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_L2B_OTHER_HAIRCUT
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_L2B_RMBS_HAIRCUT
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_LEVEL2B_CAP
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_LEVEL2_CAP
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_OPERATIONAL_DEPOSIT_INFLOW
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_OPERATIONAL_DEPOSIT_RUNOFF
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_OTHER_CONTRACTUAL_OUTFLOW
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_RETAIL_LESS_STABLE_RUNOFF
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_RETAIL_LOAN_INFLOW
import com.openbank.risk.domain.liquidity.LiquidityFactor.LCR_RETAIL_STABLE_RUNOFF
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_ASF_CAPITAL
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_ASF_OPERATIONAL_DEPOSIT
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_ASF_OTHER
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_ASF_RETAIL_LESS_STABLE
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_ASF_RETAIL_STABLE
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_CASH_AND_RESERVES
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_FI_UNDER_6M
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_L1_SECURITIES
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_L2A
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_L2B
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_LOAN_1Y_LOW_RW
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_LOAN_1Y_OTHER
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_LOAN_UNDER_1Y
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_OPERATIONAL_DEPOSIT_AT_FI
import com.openbank.risk.domain.liquidity.LiquidityFactor.NSFR_RSF_OTHER_ASSET
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The EU parameter set (Delegated Regulation (EU) 2015/61 for the LCR, CRR Part Six Title IV for the
 * NSFR), read from application.yaml. It is the DEFAULT for this bank (#10860). Each test asserts the
 * value AND the article the factor cites, so a changed figure without a changed citation goes red.
 */
class EuLiquidityParametersTest {

    private val eu = LiquidityTestParameters.shipped(LiquidityTestParameters.EU_SET)
    private val bcbs = LiquidityTestParameters.shipped(LiquidityTestParameters.BCBS_SET)

    private fun assertFactor(f: LiquidityFactor, value: String, cites: String) {
        assertThat(eu[f]).describedAs("${f.key} (${eu.citation(f)})").isEqualByComparingTo(value)
        assertThat(eu.citation(f)).describedAs(f.key).contains(cites)
    }

    @Test
    fun `the EU set is the default, identified, versioned and cites EU law`() {
        val default = LiquidityTestParameters.shipped()
        assertThat(default.id).isEqualTo("eu-2015-61-crr2")
        assertThat(default.version).isEqualTo("1")
        assertThat(default.regime).isEqualTo(LiquidityRegime.EU)
        assertThat(default.source).contains("2015/61").contains("575/2013")
        assertThat(LiquidityFactor.entries.map { default.citation(it) })
            .allMatch { it.startsWith("EU 2015/61 Art.") || it.startsWith("CRR ") }
    }

    @Test
    fun `the BCBS set stays declared and selectable`() {
        assertThat(bcbs.id).isEqualTo("bcbs-d238-d295")
        assertThat(bcbs.regime).isEqualTo(LiquidityRegime.BCBS)
    }

    @Test
    fun `2015-61 Art 10(1) - Level 1 no haircut`() = assertFactor(LCR_L1_HAIRCUT, "0", "Art. 10(1)")

    @Test
    fun `2015-61 Art 11(2) - Level 2A 15 percent haircut`() = assertFactor(LCR_L2A_HAIRCUT, "0.15", "Art. 11(2)")

    @Test
    fun `2015-61 Art 13(14)(a) - residential securitisations 25 percent haircut`() =
        assertFactor(LCR_L2B_RMBS_HAIRCUT, "0.25", "Art. 13(14)(a)")

    @Test
    fun `2015-61 Art 12 - other Level 2B 50 percent haircut, paragraph unverified`() {
        assertFactor(LCR_L2B_OTHER_HAIRCUT, "0.50", "Art. 12(1)")
        assertThat(eu.citation(LCR_L2B_OTHER_HAIRCUT)).contains("UNVERIFIED")
    }

    @Test
    fun `2015-61 Art 17(1)(b),(c) - the Level 2 and 2B caps`() {
        assertFactor(LCR_LEVEL2_CAP, "0.40", "Art. 17(1)(b)")
        assertFactor(LCR_LEVEL2B_CAP, "0.15", "Art. 17(1)(c)")
    }

    @Test
    fun `2015-61 Art 24(1) - stable retail 5 percent`() = assertFactor(LCR_RETAIL_STABLE_RUNOFF, "0.05", "Art. 24(1)")

    @Test
    fun `2015-61 Art 25(1) - other retail 10 percent`() =
        assertFactor(LCR_RETAIL_LESS_STABLE_RUNOFF, "0.10", "Art. 25(1)")

    @Test
    fun `2015-61 Art 27 - operational deposits 25 percent`() =
        assertFactor(LCR_OPERATIONAL_DEPOSIT_RUNOFF, "0.25", "Art. 27(1)(a)")

    @Test
    fun `2015-61 Art 22 - other liabilities 100 percent, paragraph unverified`() {
        assertFactor(LCR_OTHER_CONTRACTUAL_OUTFLOW, "1.00", "Art. 22(1)")
        assertThat(eu.citation(LCR_OTHER_CONTRACTUAL_OUTFLOW)).contains("UNVERIFIED")
    }

    @Test
    fun `2015-61 Art 32 - inflows 50, 100 and 0 percent`() {
        assertFactor(LCR_RETAIL_LOAN_INFLOW, "0.50", "Art. 32(3)(a)")
        assertFactor(LCR_FI_INFLOW, "1.00", "Art. 32(2)(a)")
        assertFactor(LCR_OPERATIONAL_DEPOSIT_INFLOW, "0", "Art. 32(3)(d)")
    }

    @Test
    fun `2015-61 Art 33(1) - inflows capped at 75 percent`() = assertFactor(LCR_INFLOW_CAP, "0.75", "Art. 33(1)")

    @Test
    fun `CRR Art 428k to 428o - ASF factors`() {
        assertFactor(NSFR_ASF_CAPITAL, "1.00", "Art. 428o")
        assertFactor(NSFR_ASF_RETAIL_STABLE, "0.95", "Art. 428n")
        assertFactor(NSFR_ASF_RETAIL_LESS_STABLE, "0.90", "Art. 428m")
        assertFactor(NSFR_ASF_OPERATIONAL_DEPOSIT, "0.50", "Art. 428l")
        assertFactor(NSFR_ASF_OTHER, "0", "Art. 428k")
    }

    @Test
    fun `CRR Art 428r(1) - Level 1 securities 0 percent RSF, where d295 para 37 says 5 percent`() {
        assertFactor(NSFR_RSF_L1_SECURITIES, "0", "Art. 428r(1)")
        assertThat(bcbs[NSFR_RSF_L1_SECURITIES]).isEqualByComparingTo("0.05")
        assertFactor(NSFR_RSF_CASH_AND_RESERVES, "0", "Art. 428r(1)")
    }

    @Test
    fun `CRR Art 428ad to 428ah - loan and deposit RSF factors`() {
        assertFactor(NSFR_RSF_OPERATIONAL_DEPOSIT_AT_FI, "0.50", "Art. 428ad")
        assertFactor(NSFR_RSF_LOAN_UNDER_1Y, "0.50", "Art. 428ad")
        assertFactor(NSFR_RSF_LOAN_1Y_LOW_RW, "0.65", "Art. 428ag")
        assertFactor(NSFR_RSF_LOAN_1Y_OTHER, "0.85", "Art. 428ah")
        assertFactor(NSFR_RSF_OTHER_ASSET, "1.00", "Art. 428ah")
    }

    @Test
    fun `unverified EU factors keep the BCBS value and say so`() {
        listOf(NSFR_RSF_L2A, NSFR_RSF_L2B, NSFR_RSF_FI_UNDER_6M).forEach { f ->
            assertThat(eu[f]).describedAs(f.key).isEqualByComparingTo(bcbs[f])
            assertThat(eu.citation(f)).describedAs(f.key).contains("UNVERIFIED")
        }
    }

    @Test
    fun `only the Level 1 securities RSF differs in value from the BCBS set`() {
        val differing = LiquidityFactor.entries.filter { eu[it].compareTo(bcbs[it]) != 0 }
        assertThat(differing).containsExactly(NSFR_RSF_L1_SECURITIES)
    }

    @Test
    fun `the CNB deposit facility GL 1510 is Level 1 central-bank reserves under 2015-61 Art 10(1)`() {
        assertThat(eu.classification.classOf("1510", "ASSET")).isEqualTo(GlClass.HQLA_L1_CASH_OR_RESERVES)
        val r = Liquidity.compute(
            listOf(Position(PositionKind.GL_ACCOUNT, "1510", "ASSET", "CZK", null, BigDecimal("1000"))),
            emptyList(),
            Fixtures.AS_OF,
            eu,
        ).total!!
        val line = r.lcr.hqla.lines.single()
        assertThat(line.level).isEqualTo(HqlaLevel.LEVEL_1)
        assertThat(r.lcr.hqla.stock).isEqualByComparingTo("1000")
        assertThat(r.nsfr.totalRsf).isEqualByComparingTo("0")
        assertThat(r.nsfr.rsf.single().citation).contains("Art. 428r(1)")
    }
}
