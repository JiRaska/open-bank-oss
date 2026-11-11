// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.domain.monitoring

import java.time.Instant

/**
 * What one pinned COUNT feature means over the counters: which currency, band, direction and rail,
 * and over how long a window (ADR-0371 §2, §3).
 *
 * A pack rule pins a feature by id, revision and [contentHash]. The hash covers every field here
 * and the counter scheme the band belongs to, so changing a window or a band produces a different
 * feature that an already approved pack does not match.
 */
data class CountFeatureDefinition(
    val id: String,
    val revision: Int,
    val schemeId: String,
    val schemeRevision: Int,
    val currency: String,
    val direction: MovementDirection?,
    val rail: String?,
    val band: String,
    val windowSeconds: Long,
) {
    init {
        require(id.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) { "invalid feature id" }
        require(revision > 0 && schemeRevision > 0) { "revisions are positive" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "feature currency is three upper-case letters" }
        require(rail == null || rail.matches(Regex("[A-Z][A-Z0-9_]{0,31}"))) { "invalid rail" }
        require(band.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "invalid band" }
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) { "window out of range" }
    }

    /** The text the hash is taken over. `*` stands for "any". */
    val canonical: String =
        listOf(
            id, revision, schemeId, schemeRevision, currency, direction?.name ?: ANY, rail ?: ANY, band, windowSeconds,
        ).joinToString("|")

    /** Tumbling window containing [at]: the identity of "the same window" for one open case. */
    fun windowStart(at: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(at.epochSecond, windowSeconds) * windowSeconds)

    companion object {
        const val ANY = "*"
        const val MAX_WINDOW_SECONDS = 366L * 86_400L
    }
}
