// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.capital.CapitalClassification
import com.openbank.risk.domain.capital.CapitalGlClass
import com.openbank.risk.domain.capital.CapitalParameters
import com.openbank.risk.domain.capital.RetailTreatment
import com.openbank.risk.domain.capital.ScraGrade
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.math.BigDecimal

/**
 * `openbank.risk.capital.sa.*` — the versioned Pillar 1 credit-risk parameter set (ADR-0313
 * phase 2). A config MAPPING: every member is required, so a deployment missing a risk weight fails
 * at startup instead of answering with a silently defaulted RWA. Values live in application.yaml,
 * each with its BCBS d424 / bcbs189 paragraph.
 */
@ConfigMapping(prefix = "openbank.risk.capital.sa")
interface CapitalConfig {
    @WithName("parameter-set-id")
    fun parameterSetId(): String

    @WithName("parameter-set-version")
    fun parameterSetVersion(): String

    fun source(): String

    /** `<factor-key>: <decimal>`; keys are [com.openbank.risk.domain.capital.CapitalFactor.key]. */
    fun factors(): Map<String, BigDecimal>

    fun classification(): Classification

    interface Classification {
        @WithName("retail-treatment")
        fun retailTreatment(): String

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

fun CapitalConfig.toParameters(): CapitalParameters = CapitalParameters.fromKeys(
    id = parameterSetId(),
    version = parameterSetVersion(),
    source = source(),
    factorsByKey = factors(),
    classification = classification().let { c ->
        CapitalClassification(
            retailTreatment = RetailTreatment.parse(c.retailTreatment()),
            bankScraGrade = ScraGrade.parse(c.bankScraGrade()),
            domesticCurrency = c.domesticCurrency().trim().uppercase(),
            glAccounts = c.glAccounts().mapKeys { it.key.trim() }.mapValues { CapitalGlClass.parse(it.value) },
            glAccountTypes = c.glAccountTypes().mapKeys {
                it.key.trim().uppercase()
            }.mapValues { CapitalGlClass.parse(it.value) },
        )
    },
)
