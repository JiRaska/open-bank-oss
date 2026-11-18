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
            val cleaned = iban.replace(" ", "").uppercase()
            if (cleaned.length < MIN_LENGTH || !SHAPE.matches(cleaned)) return false
            if (REGISTRY_LENGTHS[cleaned.substring(0, 2)] != cleaned.length) return false
            if (cleaned.substring(2, 4).toInt() !in MIN_CHECK..MAX_CHECK) return false
            val rearranged = cleaned.substring(4) + cleaned.substring(0, 4)
            val numeric = rearranged.map { c ->
                if (c.isDigit()) c.toString() else (c.code - 'A'.code + LETTER_OFFSET).toString()
            }.joinToString("")
            return numeric.toBigInteger().mod(MODULUS.toBigInteger()).toInt() == 1
        }

        private const val MIN_LENGTH = 15
        private const val MIN_CHECK = 2
        private const val MAX_CHECK = 98
        private const val LETTER_OFFSET = 10
        private const val MODULUS = 97
        private val SHAPE = Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]+")

        /** IBAN length per country, from the SWIFT IBAN registry. */
        private val REGISTRY_LENGTHS: Map<String, Int> = mapOf(
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
        )
    }
}
