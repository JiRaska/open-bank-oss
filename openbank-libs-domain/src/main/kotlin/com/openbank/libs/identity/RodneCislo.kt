// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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
 *   is 10 the check digit is 0. That quirk applies only to birth years up to 1985; numbers
 *   issued for later years are always divisible by 11, so remainder 10 is rejected from 1986.
 * - **9-digit** (pre-1954): `YYMMDD` + 3 serial digits, no checksum; structure and date only.
 *   Accepted only for `YY` 00..53, since no 9-digit number was issued for a later birth year.
 *
 * Month encoding carries gender and an overflow convention: women add 50 to the month;
 * since 2004, when a day's serial range is exhausted, 20 is added as well (men `+20`,
 * women `+70`). The `+20`/`+70` overflow only exists for birth years 2004 and later, so it
 * fixes the century to the 2000s and is rejected for `YY` below 04.
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
        if (!cleaned.all { it in '0'..'9' }) return Invalid("non-digit characters")
        if (cleaned.length != 9 && cleaned.length != 10) return Invalid("length must be 9 or 10 digits")
        val nineDigit = cleaned.length == NINE_DIGIT_LENGTH

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
        if (nineDigit && yy > LAST_NINE_DIGIT_YY) return Invalid("9-digit form was issued only before 1954")
        if (nineDigit && forces2000s) return Invalid("9-digit form never carries the +20/+70 overflow")
        if (forces2000s && yy < FIRST_OVERFLOW_YY) return Invalid("month overflow (+20/+70) exists only from 2004")

        val year = when {
            forces2000s -> 2000 + yy
            nineDigit -> 1900 + yy
            yy > LAST_NINE_DIGIT_YY -> 1900 + yy
            else -> 2000 + yy
        }

        val birthdate = runCatching { LocalDate.of(year, month, day) }
            .getOrElse { return Invalid("invalid date $year-$month-$day") }

        if (cleaned.length == 10) {
            val firstNine = cleaned.substring(0, 9).toLong()
            val checkDigit = cleaned.substring(9, 10).toInt()
            val remainder = (firstNine % 11).toInt()
            if (remainder == REMAINDER_QUIRK && year > LAST_QUIRK_YEAR) {
                return Invalid("checksum remainder 10 is valid only for birth years up to 1985")
            }
            val expected = if (remainder == REMAINDER_QUIRK) 0 else remainder
            if (checkDigit != expected) return Invalid("checksum mismatch")
        }

        return Parsed(canonical = cleaned, birthdate = birthdate, gender = gender)
    }

    private const val NINE_DIGIT_LENGTH = 9
    private const val LAST_NINE_DIGIT_YY = 53
    private const val FIRST_OVERFLOW_YY = 4
    private const val REMAINDER_QUIRK = 10
    private const val LAST_QUIRK_YEAR = 1985

    /** True iff [raw] is a structurally valid RČ. */
    fun isValid(raw: String): Boolean = parse(raw) is Parsed
}
