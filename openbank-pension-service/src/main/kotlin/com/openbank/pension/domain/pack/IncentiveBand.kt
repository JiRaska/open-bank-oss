// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.pack

import java.math.BigDecimal

/**
 * One progressive band of a MATCHING incentive (ADR-0334 S3): the part of a period's contribution
 * in `[from, to)` is matched at [rate]. An open last band has `to = null`.
 *
 * Bands are additive to the S1 pack model: a rule without bands keeps the single-rate meaning.
 */
data class IncentiveBand(val from: BigDecimal, val to: BigDecimal? = null, val rate: BigDecimal) {
    init {
        require(from.signum() >= 0) { "band 'from' must not be negative" }
        require(to == null || to > from) { "band 'to' must be above 'from'" }
        require(rate.signum() >= 0) { "band rate must not be negative" }
    }

    /** The part of [amount] that falls inside this band. */
    fun portionOf(amount: BigDecimal): BigDecimal {
        val upper = to?.let { amount.min(it) } ?: amount
        return (upper - from).max(BigDecimal.ZERO)
    }

    companion object {
        /** Bands must be contiguous-or-gapped but never overlap, ordered, with at most one open band, last. */
        fun validate(ruleId: String, bands: List<IncentiveBand>) {
            require(bands.isNotEmpty()) { "incentive $ruleId: bands must not be empty" }
            bands.zipWithNext().forEach { (a, b) ->
                require(a.to != null && b.from >= a.to) { "incentive $ruleId: bands must be ordered and not overlap" }
            }
        }

        /** Uncapped matched amount of [perPeriod] under [rule]: banded when the rule has bands, else `rate ×`. */
        fun matched(rule: IncentiveRule, perPeriod: BigDecimal): BigDecimal =
            rule.bands?.fold(BigDecimal.ZERO) { acc, band -> acc + band.portionOf(perPeriod).multiply(band.rate) }
                ?: perPeriod.multiply(requireNotNull(rule.rate) { "incentive ${rule.id} has neither rate nor bands" })
    }
}
