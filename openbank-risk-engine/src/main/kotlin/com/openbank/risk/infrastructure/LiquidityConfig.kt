// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.liquidity.GlClass
import com.openbank.risk.domain.liquidity.LiquidityClassification
import com.openbank.risk.domain.liquidity.LiquidityParameters
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.math.BigDecimal

/**
 * `openbank.risk.liquidity.*` — the versioned LCR / NSFR parameter set (ADR-0313 phase 1).
 *
 * A config MAPPING, not optional properties: every member is required, so a deployment missing a
 * factor fails at startup (SmallRye validates mappings at boot) instead of answering with a
 * silently defaulted ratio. The values live in application.yaml, each with its BCBS paragraph.
 */
@ConfigMapping(prefix = "openbank.risk.liquidity")
interface LiquidityConfig {
    @WithName("parameter-set-id")
    fun parameterSetId(): String

    @WithName("parameter-set-version")
    fun parameterSetVersion(): String

    fun source(): String

    /** `<factor-key>: <decimal>`; keys are [com.openbank.risk.domain.liquidity.LiquidityFactor.key]. */
    fun factors(): Map<String, BigDecimal>

    fun classification(): Classification

    interface Classification {
        @WithName("retail-stable-share")
        fun retailStableShare(): BigDecimal

        @WithName("operational-deposit-share")
        fun operationalDepositShare(): BigDecimal

        @WithName("tier2-over-one-year-share")
        fun tier2OverOneYearShare(): BigDecimal

        @WithName("loans-qualify-for-low-risk-weight")
        fun loansQualifyForLowRiskWeight(): Boolean

        /** GL code → class wire name. */
        @WithName("gl-accounts")
        fun glAccounts(): Map<String, String>

        /** GL account TYPE (INCOME, EXPENSE, …) → class, for accounts [glAccounts] does not name. */
        @WithName("gl-account-types")
        fun glAccountTypes(): Map<String, String>
    }
}

fun LiquidityConfig.toParameters(): LiquidityParameters = LiquidityParameters.fromKeys(
    id = parameterSetId(),
    version = parameterSetVersion(),
    source = source(),
    factorsByKey = factors(),
    classification = classification().let { c ->
        LiquidityClassification(
            retailStableShare = c.retailStableShare(),
            operationalDepositShare = c.operationalDepositShare(),
            tier2OverOneYearShare = c.tier2OverOneYearShare(),
            loansQualifyForLowRiskWeight = c.loansQualifyForLowRiskWeight(),
            glAccounts = c.glAccounts().mapKeys { it.key.trim() }.mapValues { GlClass.parse(it.value) },
            glAccountTypes = c.glAccountTypes().mapKeys {
                it.key.trim().uppercase()
            }.mapValues { GlClass.parse(it.value) },
        )
    },
)
