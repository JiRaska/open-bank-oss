// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.liquidity.GlClass
import com.openbank.risk.domain.liquidity.LiquidityClassification
import com.openbank.risk.domain.liquidity.LiquidityParameters
import com.openbank.risk.domain.liquidity.LiquidityRegime
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.math.BigDecimal

/**
 * `openbank.risk.liquidity.*` — the versioned LCR / NSFR parameter sets (ADR-0313 phase 1).
 *
 * Several sets can be declared under `parameter-sets.<id>` (BCBS d238/d295 and the EU Delegated
 * Regulation 2015/61 / CRR2 set); `parameter-set-id` selects the one applied. Naming a set that is
 * not declared fails at startup. The classification is shared by all sets.
 *
 * A config MAPPING, not optional properties: every member is required, so a deployment missing a
 * factor fails at startup (SmallRye validates mappings at boot) instead of answering with a
 * silently defaulted ratio. The values live in application.yaml, each with its paragraph.
 */
@ConfigMapping(prefix = "openbank.risk.liquidity")
interface LiquidityConfig {
    /** The id of the set in [parameterSets] that is applied. */
    @WithName("parameter-set-id")
    fun parameterSetId(): String

    @WithName("parameter-sets")
    fun parameterSets(): Map<String, ParameterSet>

    interface ParameterSet {
        fun version(): String

        /** `bcbs` or `eu` — which citation each factor carries. */
        fun regime(): String

        fun source(): String

        /** `<factor-key>: <decimal>`; keys are [com.openbank.risk.domain.liquidity.LiquidityFactor.key]. */
        fun factors(): Map<String, BigDecimal>
    }

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

fun LiquidityConfig.toParameters(): LiquidityParameters = toParameters(parameterSetId())

/** The set declared under `parameter-sets.<id>`, with the shared classification. */
fun LiquidityConfig.toParameters(id: String): LiquidityParameters {
    val set = requireNotNull(parameterSets()[id]) {
        "liquidity parameter set '$id' is not declared; declared: ${parameterSets().keys.sorted().joinToString()}"
    }
    return LiquidityParameters.fromKeys(
        id = id,
        version = set.version(),
        source = set.source(),
        factorsByKey = set.factors(),
        classification = classification().toDomain(),
        regime = LiquidityRegime.parse(set.regime()),
    )
}

private fun LiquidityConfig.Classification.toDomain(): LiquidityClassification = LiquidityClassification(
    retailStableShare = retailStableShare(),
    operationalDepositShare = operationalDepositShare(),
    tier2OverOneYearShare = tier2OverOneYearShare(),
    loansQualifyForLowRiskWeight = loansQualifyForLowRiskWeight(),
    glAccounts = glAccounts().mapKeys { it.key.trim() }.mapValues { GlClass.parse(it.value) },
    glAccountTypes = glAccountTypes().mapKeys { it.key.trim().uppercase() }.mapValues { GlClass.parse(it.value) },
)
