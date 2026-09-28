// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.infrastructure.CapitalConfig
import com.openbank.risk.infrastructure.toParameters
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource

/** The parameter set exactly as application.yaml ships it, loaded through [CapitalConfig]. */
object CapitalTestParameters {
    fun shipped(): CapitalParameters {
        val url = requireNotNull(CapitalTestParameters::class.java.classLoader.getResource("application.yaml"))
        val config = SmallRyeConfigBuilder()
            .withSources(YamlConfigSource(url))
            .withMapping(CapitalConfig::class.java)
            .build()
        return config.getConfigMapping(CapitalConfig::class.java).toParameters()
    }

    /** [shipped] with a different classification; the factors are never touched. */
    fun withClassification(
        retailTreatment: RetailTreatment? = null,
        bankScraGrade: ScraGrade? = null,
        glAccounts: Map<String, CapitalGlClass>? = null,
    ): CapitalParameters {
        val base = shipped()
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
