// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.infrastructure.LiquidityConfig
import com.openbank.risk.infrastructure.toParameters
import io.smallrye.config.SmallRyeConfigBuilder
import io.smallrye.config.source.yaml.YamlConfigSource

/** The parameter set exactly as application.yaml ships it, loaded through [LiquidityConfig]. */
object LiquidityTestParameters {
    fun shipped(): LiquidityParameters {
        val url = requireNotNull(LiquidityTestParameters::class.java.classLoader.getResource("application.yaml"))
        val config = SmallRyeConfigBuilder()
            .withSources(YamlConfigSource(url))
            .withMapping(LiquidityConfig::class.java)
            .build()
        return config.getConfigMapping(LiquidityConfig::class.java).toParameters()
    }

    /** [shipped] with a different classification; the factors are never touched. */
    fun withClassification(
        glAccounts: Map<String, GlClass>? = null,
        retailStableShare: String? = null,
        operationalDepositShare: String? = null,
    ): LiquidityParameters {
        val base = shipped()
        val c = base.classification
        return base.copy(
            classification = c.copy(
                glAccounts = glAccounts ?: c.glAccounts,
                retailStableShare = retailStableShare?.toBigDecimal() ?: c.retailStableShare,
                operationalDepositShare = operationalDepositShare?.toBigDecimal() ?: c.operationalDepositShare,
            ),
        )
    }
}
