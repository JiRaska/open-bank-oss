// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import java.time.DateTimeException
import java.time.LocalDate

// Small pure helpers shared by the EU VAT national rules (EuVatNationalRules.kt).

/** An ASCII decimal digit. `Char.isDigit` accepts every Unicode digit (Arabic-Indic, ...), which `toLong()` then reads as ASCII. */
internal fun Char.isAsciiDigit() = this in '0'..'9'

internal fun vatDigits(s: String) = s.isNotEmpty() && s.all(Char::isAsciiDigit)

internal fun vatDigit(s: String, i: Int) = s[i] - '0'

/** Sum of digit i times weight i over the weight table's length. */
internal fun vatWeighted(s: String, w: IntArray) = w.indices.sumOf { vatDigit(s, it) * w[it] }

internal fun vatLuhnOk(s: String) = IdentifierChecksums.luhn(s)

/** Check digit that makes `payload + digit` Luhn-valid. */
@Suppress("MagicNumber")
internal fun vatLuhnDigit(payload: String) = (0..9).first { vatLuhnOk(payload + it) }

/** ISO 7064 MOD 97-10 over the alphanumeric expansion (A=10 .. Z=35); valid when the remainder is 1. */
@Suppress("MagicNumber")
internal fun vatMod97(v: String): Boolean {
    var rem = 0
    for (ch in v) {
        val expanded = if (ch.isAsciiDigit()) (ch - '0').toString() else (ch - 'A' + 10).toString()
        for (c in expanded) rem = (rem * 10 + (c - '0')) % 97
    }
    return rem == 1
}

internal fun vatDate(year: Int, month: Int, day: Int): LocalDate? = try {
    LocalDate.of(year, month, day)
} catch (_: DateTimeException) {
    null
}

internal fun vatValidDate(year: Int, month: Int, day: Int) = vatDate(year, month, day) != null
