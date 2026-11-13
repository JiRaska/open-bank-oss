// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.max

/**
 * The arithmetic behind the index-driven delta match (ADR-0376, V23).
 *
 * The delta scores every monitored name by `word_similarity(name, entry text)`, and pg_trgm can
 * index only the other operand. This object derives a condition the trigram index CAN serve and
 * that every true match satisfies, so the index narrows the candidates and the exact function
 * still decides.
 *
 * For a name with `k` distinct trigrams and an entry text with `q`:
 *  - `word_similarity` is `c / (k + e - c)` for some extent of the entry text with `e` distinct
 *    trigrams, `c` of them shared with the name. Since `e >= c`, the score is at most `c / k`,
 *    so a score of `t` or more needs `c >= t * k` shared trigrams;
 *  - the name then shares at least that many with the whole text, so its plain `similarity()`
 *    to the text, `shared / (k + q - shared)`, is at least `m / (k + q - m)` with `m = ceil(t * k)`.
 *
 * That floor depends on `k`. Names are therefore grouped into size classes of similar `k`
 * (the `size_class` column), and each class is probed with `search_name % entry` under the
 * floor of its smallest `m` and largest `k`: [probeThreshold]. A class is a few adjacent values
 * of `k`, so the floor stays close to the exact one for every name in it.
 */
internal object MonitoringNameIndex {

    /**
     * Lower bound of `k` for size classes 1..15, the array of `width_bucket` in V23. Class `i`
     * holds `LOWER_BOUNDS[i - 1] <= k < LOWER_BOUNDS[i]`; the last one ends at [MAX_TRIGRAMS].
     */
    @Suppress("MagicNumber") // the bounds are the definition, and they are the array V23 declares
    val LOWER_BOUNDS = listOf(1, 3, 5, 7, 10, 14, 20, 29, 43, 64, 96, 192, 384, 768, 1536)

    /** Longest stored search name, the CHECK of V23. */
    const val MAX_NAME_LENGTH = 2000

    /** A name of `n` characters has at most `n + 1` trigrams: each word of `l` characters has `l + 1`. */
    const val MAX_TRIGRAMS = MAX_NAME_LENGTH + 1

    val CLASSES: IntRange = 1..LOWER_BOUNDS.size

    fun smallest(sizeClass: Int): Int = LOWER_BOUNDS[sizeClass - 1]

    fun largest(sizeClass: Int): Int = if (sizeClass == LOWER_BOUNDS.size) MAX_TRIGRAMS else LOWER_BOUNDS[sizeClass] - 1

    /** Shared trigrams a name of [trigrams] needs at least to score [threshold]; never below one. */
    fun minShared(trigrams: Int, threshold: Double): Int = max(1, ceil((threshold - SCORE_SLACK) * trigrams).toInt())

    /**
     * The `pg_trgm.similarity_threshold` under which `search_name % entry` keeps every name of
     * [sizeClass] that can score [threshold] against an entry text of [entryTrigrams] trigrams.
     *
     * Rounded down, and both slacks lower it: pg_trgm computes in single precision, and a floor
     * that is a hair too high would drop a true match, while one too low only admits a candidate
     * the exact score then rejects.
     *
     * @return null when no name of the class can reach the score at all: even its smallest name
     *   needs more shared trigrams than the entry text has. The class is then not probed.
     */
    fun probeThreshold(sizeClass: Int, entryTrigrams: Int, threshold: Double): BigDecimal? {
        require(threshold > 0.0 && threshold <= 1.0) { "threshold must be in (0, 1]" }
        require(entryTrigrams > 0) { "an entry text without trigrams matches nothing and is not probed" }
        val shared = minShared(smallest(sizeClass), threshold)
        if (shared > entryTrigrams) return null
        val floor = shared.toDouble() / (largest(sizeClass) + entryTrigrams - shared)
        return BigDecimal(floor * (1 - PROBE_SLACK), MathContext.DECIMAL64).setScale(GUC_SCALE, RoundingMode.DOWN)
    }

    private const val SCORE_SLACK = 0.001
    private const val PROBE_SLACK = 0.001
    private const val GUC_SCALE = 6
}
