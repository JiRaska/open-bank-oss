// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The SHIPPED parameter set, read from application.yaml through the same config mapping the
 * service uses ([LiquidityTestParameters.shipped]), holds the value its citation names. One test per
 * factor family; a changed value without a changed citation (and a version bump) goes red here.
 */
class LiquidityParametersTest {

    private val p = LiquidityTestParameters.shipped()

    private fun assertFactors(vararg expected: Pair<LiquidityFactor, String>) = expected.forEach { (f, v) ->
        assertThat(p[f]).describedAs("${f.key} (${f.citation})").isEqualByComparingTo(v)
    }

    @Test
    fun `the parameter set is identified and versioned`() {
        assertThat(p.id).isEqualTo("bcbs-d238-d295")
        assertThat(p.version).isEqualTo("1")
        assertThat(p.source).contains("d238").contains("d295").contains("2015/61 deviations not applied")
    }

    @Test
    fun `HQLA haircuts and caps are d238 49, 52, 54 and the Annex 1 caps`() = assertFactors(
        LCR_L1_HAIRCUT to "0",
        LCR_L2A_HAIRCUT to "0.15",
        LCR_L2B_RMBS_HAIRCUT to "0.25",
        LCR_L2B_OTHER_HAIRCUT to "0.50",
        LCR_LEVEL2_CAP to "0.40",
        LCR_LEVEL2B_CAP to "0.15",
    )

    @Test
    fun `outflow run-off rates are d238 75, 79, 93 and 141`() = assertFactors(
        LCR_RETAIL_STABLE_RUNOFF to "0.05",
        LCR_RETAIL_LESS_STABLE_RUNOFF to "0.10",
        LCR_OPERATIONAL_DEPOSIT_RUNOFF to "0.25",
        LCR_OTHER_CONTRACTUAL_OUTFLOW to "1.00",
    )

    @Test
    fun `inflow rates and the cap are d238 153, 154, 156 and 144`() = assertFactors(
        LCR_RETAIL_LOAN_INFLOW to "0.50",
        LCR_FI_INFLOW to "1.00",
        LCR_OPERATIONAL_DEPOSIT_INFLOW to "0",
        LCR_INFLOW_CAP to "0.75",
    )

    @Test
    fun `ASF factors are d295 21 to 25`() = assertFactors(
        NSFR_ASF_CAPITAL to "1.00",
        NSFR_ASF_RETAIL_STABLE to "0.95",
        NSFR_ASF_RETAIL_LESS_STABLE to "0.90",
        NSFR_ASF_OPERATIONAL_DEPOSIT to "0.50",
        NSFR_ASF_OTHER to "0",
    )

    @Test
    fun `RSF factors are d295 36 to 43`() = assertFactors(
        NSFR_RSF_CASH_AND_RESERVES to "0",
        NSFR_RSF_L1_SECURITIES to "0.05",
        NSFR_RSF_L2A to "0.15",
        NSFR_RSF_FI_UNDER_6M to "0.15",
        NSFR_RSF_L2B to "0.50",
        NSFR_RSF_OPERATIONAL_DEPOSIT_AT_FI to "0.50",
        NSFR_RSF_LOAN_UNDER_1Y to "0.50",
        NSFR_RSF_LOAN_1Y_LOW_RW to "0.65",
        NSFR_RSF_LOAN_1Y_OTHER to "0.85",
        NSFR_RSF_OTHER_ASSET to "1.00",
    )

    @Test
    fun `the shipped classification is the conservative one`() {
        val c = p.classification
        assertThat(c.retailStableShare).isEqualByComparingTo("0")
        assertThat(c.operationalDepositShare).isEqualByComparingTo("0")
        assertThat(c.tier2OverOneYearShare).isEqualByComparingTo("0")
        assertThat(c.loansQualifyForLowRiskWeight).isFalse()
        assertThat(c.glAccounts.values.none { it.isHqla }).describedAs("nothing is HQLA until mapped").isTrue()
        assertThat(c.glAccounts["1001"]).isEqualTo(GlClass.DEPOSIT_AT_FI_OPERATIONAL)
        assertThat(c.glAccounts).doesNotContainKey("1000")
    }

    @Test
    fun `a missing, unknown or out-of-range factor is refused, never defaulted`() {
        val keys = p.factors.mapKeys { it.key.key }
        assertThatThrownBy {
            LiquidityParameters.fromKeys("x", "1", "s", keys - LCR_INFLOW_CAP.key, p.classification)
        }.hasMessageContaining("missing factors").hasMessageContaining("lcr-inflow-cap")
        assertThatThrownBy {
            LiquidityParameters.fromKeys("x", "1", "s", keys + ("lcr-made-up" to BigDecimal.ONE), p.classification)
        }.hasMessageContaining("unknown liquidity factor keys")
        assertThatThrownBy {
            LiquidityParameters.fromKeys(
                "x",
                "1",
                "s",
                keys + (LCR_INFLOW_CAP.key to BigDecimal("1.5")),
                p.classification,
            )
        }.hasMessageContaining("[0, 1]")
    }
}
