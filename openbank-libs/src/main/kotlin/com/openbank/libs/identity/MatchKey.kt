// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.identity

import java.text.Normalizer
import java.time.LocalDate

/**
 * Normalized candidate-match key for applicants without a Czech RČ (ADR-0072, tier 2).
 *
 * There is no strong deterministic key for foreign nationals, so identity resolution falls
 * back to a conservative, *explainable* match on the tuple
 * `(family name, given name, birthdate, birthplace)`. This deliberately is not a probabilistic
 * scorer: a match here never auto-merges — it only raises a manual-verification case. The key
 * exists so two cosmetically different spellings ("Dvořák" vs "DVORAK ") collapse to the same
 * comparable value.
 *
 * Normalization: NFD decomposition, strip combining marks (diacritics), lowercase
 * (locale-invariant), collapse internal whitespace, trim. Birthplace is optional; when absent
 * it contributes an empty segment.
 */
object MatchKey {

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(WHITESPACE, " ")
        .trim()

    fun of(familyName: String, givenName: String, birthdate: LocalDate, birthplace: String?): String = listOf(
        normalize(familyName),
        normalize(givenName),
        birthdate.toString(),
        birthplace?.let { normalize(it) } ?: "",
    ).joinToString("|")
}
