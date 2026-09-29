// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.capital.CapitalClassification
import com.openbank.risk.domain.capital.CapitalGlClass
import com.openbank.risk.domain.capital.CapitalParameters
import com.openbank.risk.domain.capital.CapitalRegime
import com.openbank.risk.domain.capital.RetailTreatment
import com.openbank.risk.domain.capital.ScraGrade
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.math.BigDecimal

/**
 * `openbank.risk.capital.sa.*` — the versioned Pillar 1 credit-risk parameter sets (ADR-0313
 * phase 2). Several sets are declared under `parameter-sets.<id>` (EU CRR `eu-crr3-sa`, the
 * default, and BCBS d424 `bcbs-d424-sa`); `parameter-set-id` selects the one applied. Naming a set
 * that is not declared fails at startup. The GL classification is shared by all sets; the retail
 * treatment is per set, because the two rulebooks offer different ones.
 *
 * A config MAPPING: every member is required, so a deployment missing a risk weight fails at
 * startup instead of answering with a silently defaulted RWA. Values live in application.yaml,
 * each with its article / paragraph.
 */
@ConfigMapping(prefix = "openbank.risk.capital.sa")
interface CapitalConfig {
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

        /** How a non-defaulted loan / overdraft is weighted: [RetailTreatment] wire name. */
        @WithName("retail-treatment")
        fun retailTreatment(): String

        /** `<factor-key>: <decimal>`; keys are [com.openbank.risk.domain.capital.CapitalFactor.key]. */
        fun factors(): Map<String, BigDecimal>
    }

    fun classification(): Classification

    interface Classification {
        @WithName("bank-scra-grade")
        fun bankScraGrade(): String

        @WithName("domestic-currency")
        fun domesticCurrency(): String

        /** GL code → class wire name. */
        @WithName("gl-accounts")
        fun glAccounts(): Map<String, String>

        /** GL account TYPE → class, for accounts [glAccounts] does not name. */
        @WithName("gl-account-types")
        fun glAccountTypes(): Map<String, String>
    }
}

fun CapitalConfig.toParameters(): CapitalParameters = toParameters(parameterSetId())

/** The set declared under `parameter-sets.<id>`, with the shared classification. */
fun CapitalConfig.toParameters(id: String): CapitalParameters {
    val set = requireNotNull(parameterSets()[id]) {
        "capital parameter set '$id' is not declared; declared: ${parameterSets().keys.sorted().joinToString()}"
    }
    return CapitalParameters.fromKeys(
        id = id,
        version = set.version(),
        source = set.source(),
        factorsByKey = set.factors(),
        classification = classification().let { c ->
            CapitalClassification(
                retailTreatment = RetailTreatment.parse(set.retailTreatment()),
                bankScraGrade = ScraGrade.parse(c.bankScraGrade()),
                domesticCurrency = c.domesticCurrency().trim().uppercase(),
                glAccounts = c.glAccounts().mapKeys { it.key.trim() }.mapValues { CapitalGlClass.parse(it.value) },
                glAccountTypes = c.glAccountTypes().mapKeys {
                    it.key.trim().uppercase()
                }.mapValues { CapitalGlClass.parse(it.value) },
            )
        },
        regime = CapitalRegime.parse(set.regime()),
    )
}
