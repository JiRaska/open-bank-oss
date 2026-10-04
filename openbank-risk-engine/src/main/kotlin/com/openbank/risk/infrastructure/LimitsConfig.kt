// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.domain.limits.LimitDefinition
import com.openbank.risk.domain.limits.LimitMetric
import com.openbank.risk.domain.limits.LimitSet
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithName
import java.math.BigDecimal

/**
 * `openbank.risk.limits.*` — the versioned, declarative risk-limit sets (ADR-0313 D9), same shape as
 * the liquidity and capital parameter sets: several sets under `parameter-sets.<id>`,
 * `parameter-set-id` selects the one applied, and every evaluation names the id and version.
 *
 * A config MAPPING: every member is required, so a limit missing its early-warning figure fails at
 * startup rather than evaluating against a defaulted threshold. The domain rejects an unknown metric,
 * an early-warning on the wrong side of its limit, a non-positive figure and a duplicate id.
 */
@ConfigMapping(prefix = "openbank.risk.limits")
interface LimitsConfig {
    @WithName("parameter-set-id")
    fun parameterSetId(): String

    @WithName("parameter-sets")
    fun parameterSets(): Map<String, ParameterSet>

    interface ParameterSet {
        fun version(): String

        fun source(): String

        /** `<limit-id>: {metric, limit, early-warning, citation}`. */
        fun limits(): Map<String, Limit>
    }

    interface Limit {
        /** [LimitMetric.wire]; the metric fixes the direction (floor or ceiling). */
        fun metric(): String

        fun limit(): BigDecimal

        @WithName("early-warning")
        fun earlyWarning(): BigDecimal

        fun citation(): String
    }
}

fun LimitsConfig.toLimitSet(): LimitSet = toLimitSet(parameterSetId())

fun LimitsConfig.toLimitSet(id: String): LimitSet {
    val set = requireNotNull(parameterSets()[id]) {
        "limit set '$id' is not declared; declared: ${parameterSets().keys.sorted().joinToString()}"
    }
    return LimitSet(
        id = id,
        version = set.version(),
        source = set.source(),
        limits = set.limits().toSortedMap().map { (limitId, l) ->
            LimitDefinition(
                id = limitId.trim(),
                metric = LimitMetric.parse(l.metric()),
                limit = l.limit(),
                earlyWarning = l.earlyWarning(),
                citation = l.citation(),
            )
        },
    )
}
