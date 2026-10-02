// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import java.math.BigDecimal

/** Which side of the limit is safe: a floor (ratio must stay at or above) or a ceiling. */
enum class LimitBound { MIN, MAX }

/**
 * The closed set of figures a limit can be declared on (ADR-0313 D9). The DIRECTION is part of the
 * metric, not of the configuration, so a limit cannot be declared on the wrong side of its figure.
 *
 * Deliberately absent: an OPEN FX POSITION per currency. The snapshot has no FX forwards, swaps or
 * derivative legs, and the ledger's FX revaluation accounts 1990-1997 are counter-value accounts the
 * capital classification leaves unmodelled ("not-an-exposure"): a net open position computed from
 * balance-sheet currency totals alone would state a figure the data does not support.
 */
enum class LimitMetric(val wire: String, val bound: LimitBound, val description: String) {
    LCR("lcr", LimitBound.MIN, "Liquidity coverage ratio, CZK total (HQLA / net outflows over 30 days)"),
    NSFR("nsfr", LimitBound.MIN, "Net stable funding ratio, CZK total (ASF / RSF)"),
    TOTAL_CAPITAL_RATIO(
        "total-capital-ratio",
        LimitBound.MIN,
        "Total capital ratio: own funds / credit-risk RWA (an upper bound: no market or operational risk RWA)",
    ),
    IRRBB_EVE_OUTLIER(
        "irrbb-eve-outlier",
        LimitBound.MAX,
        "Worst supervisory-scenario EVE loss / Tier 1 capital (supervisory outlier test)",
    ),
    LARGE_EXPOSURE_BANK(
        "large-exposure-bank",
        LimitBound.MAX,
        "Largest exposure to a single bank counterparty / Tier 1 capital",
    ),
    ;

    companion object {
        fun parse(raw: String): LimitMetric = entries.firstOrNull { it.wire == raw.trim().lowercase() }
            ?: throw IllegalArgumentException("unknown limit metric '$raw'; one of ${entries.joinToString { it.wire }}")
    }
}

/**
 * One declared limit. [earlyWarning] sits on the SAFE side of [limit] (above a floor, below a
 * ceiling), so a figure crosses the warning before it crosses the limit; equal to [limit] means no
 * early-warning band.
 */
data class LimitDefinition(
    val id: String,
    val metric: LimitMetric,
    val limit: BigDecimal,
    val earlyWarning: BigDecimal,
    val citation: String,
) {
    init {
        require(ID.matches(id)) { "limit id '$id' must be lower-kebab-case" }
        require(limit.signum() > 0) { "limit '$id': limit must be positive, was $limit" }
        require(earlyWarning.signum() > 0) { "limit '$id': early-warning must be positive, was $earlyWarning" }
        when (metric.bound) {
            LimitBound.MIN -> require(earlyWarning >= limit) {
                "limit '$id' is a floor: early-warning $earlyWarning must be at or above the limit $limit"
            }
            LimitBound.MAX -> require(earlyWarning <= limit) {
                "limit '$id' is a ceiling: early-warning $earlyWarning must be at or below the limit $limit"
            }
        }
        require(citation.isNotBlank()) { "limit '$id' needs a citation" }
    }

    private companion object {
        val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}

/** A versioned set of limits: bump [version] on any change, every evaluation names id and version. */
data class LimitSet(val id: String, val version: String, val source: String, val limits: List<LimitDefinition>) {
    init {
        require(id.isNotBlank()) { "limit set id must not be blank" }
        require(version.isNotBlank()) { "limit set '$id' needs a version" }
        require(limits.isNotEmpty()) { "limit set '$id' declares no limits" }
        val dup = limits.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "limit set '$id' declares duplicate limit ids: ${dup.sorted()}" }
    }
}

/** What a limit is measured against: a figure, or the reason no honest figure exists. */
sealed interface MetricInput {
    data class Measured(val value: BigDecimal, val basis: String) : MetricInput

    /** The figure's inputs have a gap; the limit is NOT evaluated against a partial number. */
    data class Gap(val reason: String) : MetricInput
}

enum class LimitStatus {
    OK,
    EARLY_WARNING,
    BREACH,

    /** The input is a gap. Never a synonym for OK: it means nobody knows. */
    NOT_EVALUABLE,
}

data class LimitEvaluation(
    val definition: LimitDefinition,
    val status: LimitStatus,
    /** The measured figure; null exactly when [status] is [LimitStatus.NOT_EVALUABLE]. */
    val value: BigDecimal?,
    /** How the figure was obtained, or why it could not be. */
    val explanation: String,
)

/** Declarative risk limits evaluated per snapshot run (ADR-0313 D9). Pure: no I/O, no clock. */
object RiskLimits {

    fun evaluate(set: LimitSet, inputs: Map<LimitMetric, MetricInput>): List<LimitEvaluation> =
        set.limits.map { evaluate(it, inputs[it.metric]) }

    fun evaluate(definition: LimitDefinition, input: MetricInput?): LimitEvaluation = when (input) {
        null -> LimitEvaluation(
            definition,
            LimitStatus.NOT_EVALUABLE,
            null,
            "no figure was computed for ${definition.metric.wire}",
        )
        is MetricInput.Gap -> LimitEvaluation(definition, LimitStatus.NOT_EVALUABLE, null, input.reason)
        is MetricInput.Measured -> LimitEvaluation(
            definition,
            status(definition, input.value),
            input.value,
            input.basis,
        )
    }

    /**
     * The limit itself is inside the permitted range (a ratio exactly at the minimum meets it, as
     * `CapitalRatio.meetsMinimum` reads it); the early-warning figure itself already warns.
     */
    fun status(d: LimitDefinition, value: BigDecimal): LimitStatus = when (d.metric.bound) {
        LimitBound.MIN -> when {
            value < d.limit -> LimitStatus.BREACH
            value <= d.earlyWarning && d.earlyWarning > d.limit -> LimitStatus.EARLY_WARNING
            else -> LimitStatus.OK
        }
        LimitBound.MAX -> when {
            value > d.limit -> LimitStatus.BREACH
            value >= d.earlyWarning && d.earlyWarning < d.limit -> LimitStatus.EARLY_WARNING
            else -> LimitStatus.OK
        }
    }
}
