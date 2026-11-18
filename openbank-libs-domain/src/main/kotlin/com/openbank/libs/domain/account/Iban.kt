// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

data class Iban(val value: String) {
    init {
        require(isValid(value)) { "Invalid IBAN: $value" }
    }

    val countryCode: String get() = value.substring(0, 2)
    val checkDigits: String get() = value.substring(2, 4)
    val bban: String get() = value.substring(4)

    fun formatted(): String = value.chunked(4).joinToString(" ")

    override fun toString(): String = value

    companion object {
        fun of(raw: String): Iban = Iban(raw.replace(" ", "").uppercase())

        /**
         * ISO 13616 validation: country in the SWIFT IBAN registry, the exact registry length
         * for that country, check digits 02..98, an `[A-Z0-9]` BBAN, and mod-97 == 1. Returns
         * false (never throws) for any malformed input.
         */
        fun isValid(iban: String): Boolean {
            val needsCleaning = iban.any { it == ' ' || it in 'a'..'z' }
            val cleaned = if (needsCleaning) iban.replace(" ", "").uppercase() else iban
            return hasValidHeader(cleaned) && mod97(cleaned) == 1
        }

        /** Country in the registry with its exact length, and check digits 02..98. */
        private fun hasValidHeader(s: String): Boolean {
            if (s.length < MIN_LENGTH) return false
            if (!isUpper(s[0]) || !isUpper(s[1])) return false
            if (!isAsciiDigit(s[2]) || !isAsciiDigit(s[CHECK_END])) return false
            val check = (s[2] - '0') * DECIMAL + (s[CHECK_END] - '0')
            return check in MIN_CHECK..MAX_CHECK && REGISTRY_LENGTHS[countryIndex(s[0], s[1])] == s.length
        }

        /** ISO 7064 mod-97 over BBAN then country + check digits, incrementally; -1 on a bad char. */
        private fun mod97(s: String): Int {
            var rem = 0
            for (i in BBAN_START until s.length + BBAN_START) {
                val c = s[if (i < s.length) i else i - s.length]
                rem = when {
                    isAsciiDigit(c) -> (rem * DECIMAL + (c - '0')) % MODULUS
                    isUpper(c) -> (rem * HUNDRED + (c - 'A' + LETTER_OFFSET)) % MODULUS
                    else -> return -1
                }
            }
            return rem
        }

        private fun isUpper(c: Char) = c in 'A'..'Z'

        private fun isAsciiDigit(c: Char) = c in '0'..'9'

        private fun countryIndex(a: Char, b: Char): Int = (a - 'A') * ALPHABET + (b - 'A')

        private const val MIN_LENGTH = 15
        private const val MIN_CHECK = 2
        private const val MAX_CHECK = 98
        private const val LETTER_OFFSET = 10
        private const val MODULUS = 97
        private const val BBAN_START = 4
        private const val CHECK_END = 3
        private const val DECIMAL = 10
        private const val HUNDRED = 100
        private const val ALPHABET = 26

        /** IBAN length per country, from the SWIFT IBAN registry. */
        private val REGISTRY_LENGTHS: IntArray = IntArray(ALPHABET * ALPHABET).also { table ->
            mapOf(
                "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16,
                "BG" to 22, "BH" to 22, "BI" to 27, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22,
                "CY" to 28, "CZ" to 24, "DE" to 22, "DJ" to 27, "DK" to 18, "DO" to 28, "EE" to 20,
                "EG" to 29, "ES" to 24, "FI" to 18, "FK" to 18, "FO" to 18, "FR" to 27, "GB" to 22,
                "GE" to 22, "GI" to 23, "GL" to 18, "GR" to 27, "GT" to 28, "HN" to 28, "HR" to 21,
                "HU" to 28, "IE" to 22, "IL" to 23, "IQ" to 23, "IS" to 26, "IT" to 27, "JO" to 30,
                "KW" to 30, "KZ" to 20, "LB" to 28, "LC" to 32, "LI" to 21, "LT" to 20, "LU" to 20,
                "LV" to 21, "LY" to 25, "MC" to 27, "MD" to 24, "ME" to 22, "MK" to 19, "MN" to 20,
                "MR" to 27, "MT" to 31, "MU" to 30, "NI" to 28, "NL" to 18, "NO" to 15, "OM" to 23,
                "PK" to 24, "PL" to 28, "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22,
                "RU" to 33, "SA" to 24, "SC" to 31, "SD" to 18, "SE" to 24, "SI" to 19, "SK" to 24,
                "SM" to 27, "SO" to 23, "ST" to 25, "SV" to 28, "TL" to 23, "TN" to 24, "TR" to 26,
                "UA" to 29, "VA" to 22, "VG" to 24, "XK" to 20, "YE" to 30,
            ).forEach { (cc, n) -> table[countryIndex(cc[0], cc[1])] = n }
        }
    }
}
