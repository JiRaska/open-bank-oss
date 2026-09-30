// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.infrastructure.CapitalConfig
import com.openbank.risk.infrastructure.toParameters
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource

/** The parameter sets exactly as application.yaml ships them, loaded through [CapitalConfig]. */
object CapitalTestParameters {
    const val EU_SET = "eu-crr3-sa"
    const val BCBS_SET = "bcbs-d424-sa"

    fun config(): CapitalConfig {
        val url = requireNotNull(CapitalTestParameters::class.java.classLoader.getResource("application.yaml"))
        return SmallRyeConfigBuilder()
            .withSources(YamlConfigSource(url))
            .withMapping(CapitalConfig::class.java)
            .build()
            .getConfigMapping(CapitalConfig::class.java)
    }

    /** The set `parameter-set-id` selects: what the service computes with unless reconfigured. */
    fun shipped(): CapitalParameters = config().toParameters()

    /** The EU CRR set, by id. */
    fun eu(): CapitalParameters = config().toParameters(EU_SET)

    /** The BCBS d424 set, by id: still declared, and selectable. */
    fun bcbs(): CapitalParameters = config().toParameters(BCBS_SET)

    /** [bcbs] with a different classification; the factors are never touched. */
    fun withClassification(
        retailTreatment: RetailTreatment? = null,
        bankScraGrade: ScraGrade? = null,
        glAccounts: Map<String, CapitalGlClass>? = null,
    ): CapitalParameters {
        val base = bcbs()
        val c = base.classification
        return base.copy(
            classification = c.copy(
                retailTreatment = retailTreatment ?: c.retailTreatment,
                bankScraGrade = bankScraGrade ?: c.bankScraGrade,
                glAccounts = glAccounts ?: c.glAccounts,
            ),
        )
    }
}
