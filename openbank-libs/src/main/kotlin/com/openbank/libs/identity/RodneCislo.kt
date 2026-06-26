// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.identity

import java.time.LocalDate

/**
 * Czech birth number (rodné číslo, RČ) parser and validator (ADR-0072).
 *
 * The RČ is the deterministic identity key for Czech natural persons. This is a pure,
 * framework-free value parser: it normalizes the input, validates structure, the encoded
 * birthdate, and (for the modern 10-digit form) the mod-11 checksum, and extracts the
 * birthdate and gender so callers can cross-check them against the party's declared
 * attributes.
 *
 * Forms:
 * - **10-digit** (issued since 1954): `YYMMDD` + 3 serial digits + 1 check digit, optional
 *   slash before the last four. The whole number must be divisible by 11 — equivalently the
 *   check digit equals `firstNine mod 11`, with the historical quirk that when that remainder
 *   is 10 the check digit is 0.
 * - **9-digit** (pre-1954): `YYMMDD` + 3 serial digits, no checksum; structure and date only.
 *
 * Month encoding carries gender and an overflow convention: women add 50 to the month;
 * since 2004, when a day's serial range is exhausted, 20 is added as well (men `+20`,
 * women `+70`). The `+20`/`+70` overflow only exists in the 2000s, which disambiguates the
 * century for those records.
 *
 * This validator is deterministic and takes no clock: it does not reject "future" birthdates
 * (the caller compares the extracted [Parsed.birthdate] against the declared birthdate, which
 * is the meaningful cross-field check).
 */
object RodneCislo {

    enum class Gender { MALE, FEMALE }

    sealed interface Result

    /** A structurally valid RČ. [canonical] is digits-only (no slash), suitable for the blind index. */
    data class Parsed(val canonical: String, val birthdate: LocalDate, val gender: Gender) : Result

    data class Invalid(val reason: String) : Result

    fun parse(raw: String): Result {
        val cleaned = raw.trim().replace("/", "").replace(" ", "")
        if (cleaned.isEmpty()) return Invalid("empty")
        if (!cleaned.all { it.isDigit() }) return Invalid("non-digit characters")
        if (cleaned.length != 9 && cleaned.length != 10) return Invalid("length must be 9 or 10 digits")

        val yy = cleaned.substring(0, 2).toInt()
        val rawMonth = cleaned.substring(2, 4).toInt()
        val day = cleaned.substring(4, 6).toInt()

        // Decode gender + real month from the encoded month, and note whether the +20/+70
        // overflow convention was used (2000s-only).
        val gender: Gender
        val month: Int
        var forces2000s = false
        when (rawMonth) {
            in 1..12 -> {
                gender = Gender.MALE
                month = rawMonth
            }
            in 51..62 -> {
                gender = Gender.FEMALE
                month = rawMonth - 50
            }
            in 21..32 -> {
                gender = Gender.MALE
                month = rawMonth - 20
                forces2000s = true
            }
            in 71..82 -> {
                gender = Gender.FEMALE
                month = rawMonth - 70
                forces2000s = true
            }
            else -> return Invalid("invalid month component")
        }

        // Century: the +20/+70 overflow only exists from 2004 on. Otherwise, the 10-digit
        // form maps 54..99 → 1900s and 00..53 → 2000s; the 9-digit form is pre-1954 → 1900s.
        val year = when {
            forces2000s -> 2000 + yy
            cleaned.length == 9 -> 1900 + yy
            yy >= 54 -> 1900 + yy
            else -> 2000 + yy
        }

        val birthdate = runCatching { LocalDate.of(year, month, day) }
            .getOrElse { return Invalid("invalid date $year-$month-$day") }

        if (cleaned.length == 10) {
            val firstNine = cleaned.substring(0, 9).toLong()
            val checkDigit = cleaned.substring(9, 10).toInt()
            val remainder = (firstNine % 11).toInt()
            val expected = if (remainder == 10) 0 else remainder
            if (checkDigit != expected) return Invalid("checksum mismatch")
        }

        return Parsed(canonical = cleaned, birthdate = birthdate, gender = gender)
    }

    /** True iff [raw] is a structurally valid RČ. */
    fun isValid(raw: String): Boolean = parse(raw) is Parsed
}
