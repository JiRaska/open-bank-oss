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
         * ISO 13616 validation: country in the SWIFT IBAN registry, a BBAN matching that
         * country's registry structure (which fixes its length and which positions are digits,
         * letters or alphanumerics), check digits 02..98, and mod-97 == 1. Returns false (never
         * throws) for any malformed input. National BBAN checksums (e.g. the Czech mod-11) are
         * not part of ISO 13616 and are not checked here.
         */
        fun isValid(iban: String): Boolean {
            val cleaned = iban.replace(" ", "").uppercase()
            if (cleaned.length < MIN_LENGTH) return false
            val c0 = cleaned[0]
            val c1 = cleaned[1]
            if (!c0.isAsciiUpper() || !c1.isAsciiUpper()) return false
            val d1 = cleaned[CHECK_FIRST]
            val d2 = cleaned[CHECK_SECOND]
            if (!d1.isAsciiDigit() || !d2.isAsciiDigit()) return false
            val check = (d1 - '0') * DECIMAL + (d2 - '0')
            if (check !in MIN_CHECK..MAX_CHECK) return false
            val structure = REGISTRY_BBAN[(c0 - 'A') * ALPHABET + (c1 - 'A')] ?: return false
            return matchesStructure(cleaned, structure) && mod97(cleaned) == 1
        }

        /**
         * Walks the BBAN against its registry structure, encoded as (length, class) pairs, with
         * no substring or regex allocation (the JMH allocation baseline guards this path).
         */
        private fun matchesStructure(iban: String, structure: IntArray): Boolean {
            var pos = BBAN_START
            var i = 0
            while (i < structure.size) {
                val end = pos + structure[i]
                if (end > iban.length) return false
                val cls = structure[i + 1]
                while (pos < end) {
                    val c = iban[pos]
                    val ok = when (cls) {
                        CLASS_DIGIT -> c.isAsciiDigit()
                        CLASS_LETTER -> c.isAsciiUpper()
                        else -> c.isAsciiDigit() || c.isAsciiUpper()
                    }
                    if (!ok) return false
                    pos++
                }
                i += 2
            }
            return pos == iban.length
        }

        /** ISO 7064 MOD 97-10 over BBAN + country + check digits, streamed (A=10 … Z=35). */
        private fun mod97(iban: String): Int {
            var rem = 0
            for (k in iban.indices) {
                val c = iban[(k + BBAN_START) % iban.length]
                rem = if (c.isAsciiDigit()) {
                    (rem * DECIMAL + (c - '0')) % MODULUS
                } else {
                    (rem * HUNDRED + (c - 'A' + LETTER_OFFSET)) % MODULUS
                }
            }
            return rem
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        private fun Char.isAsciiUpper() = this in 'A'..'Z'

        private const val MIN_LENGTH = 15
        private const val MIN_CHECK = 2
        private const val MAX_CHECK = 98
        private const val LETTER_OFFSET = 10
        private const val MODULUS = 97
        private const val DECIMAL = 10
        private const val HUNDRED = 100
        private const val ALPHABET = 26
        private const val BBAN_START = 4
        private const val CHECK_FIRST = 2
        private const val CHECK_SECOND = 3
        private const val CLASS_DIGIT = 0
        private const val CLASS_LETTER = 1
        private const val CLASS_ALNUM = 2

        /**
         * BBAN structure per country, verbatim from the SWIFT IBAN registry notation (`n` digits,
         * `a` upper-case letters, `c` alphanumerics, `!` fixed length) as shipped by python-stdnum
         * 2.2 (`stdnum/iban.dat`), which the identifier differential test compares against.
         */
        private val REGISTRY_STRUCTURE: Map<String, String> = mapOf(
            "AD" to "4!n4!n12!c", "AE" to "3!n16!n", "AL" to "8!n16!c", "AT" to "5!n11!n", "AZ" to "4!a20!c",
            "BA" to "3!n3!n8!n2!n", "BE" to "3!n7!n2!n", "BG" to "4!a4!n2!n8!c", "BH" to "4!a14!c",
            "BI" to "5!n5!n11!n2!n", "BR" to "8!n5!n10!n1!a1!c", "BY" to "4!c4!n16!c", "CH" to "5!n12!c",
            "CR" to "4!n14!n", "CY" to "3!n5!n16!c", "CZ" to "4!n16!n", "DE" to "8!n10!n", "DJ" to "5!n5!n11!n2!n",
            "DK" to "4!n9!n1!n", "DO" to "4!c20!n", "EE" to "2!n14!n", "EG" to "4!n4!n17!n",
            "ES" to "4!n4!n1!n1!n10!n", "FI" to "3!n11!n", "FK" to "2!a12!n", "FO" to "4!n9!n1!n",
            "FR" to "5!n5!n11!c2!n", "GB" to "4!a6!n8!n", "GE" to "2!a16!n", "GI" to "4!a15!c", "GL" to "4!n9!n1!n",
            "GR" to "3!n4!n16!c", "GT" to "4!c20!c", "HN" to "4!a20!n", "HR" to "7!n10!n",
            "HU" to "3!n4!n1!n15!n1!n", "IE" to "4!a6!n8!n", "IL" to "3!n3!n13!n", "IQ" to "4!a3!n12!n",
            "IS" to "4!n2!n6!n10!n", "IT" to "1!a5!n5!n12!c", "JO" to "4!a4!n18!c", "KW" to "4!a22!c",
            "KZ" to "3!n13!c", "LB" to "4!n20!c", "LC" to "4!a24!c", "LI" to "5!n12!c", "LT" to "5!n11!n",
            "LU" to "3!n13!c", "LV" to "4!a13!c", "LY" to "3!n3!n15!n", "MC" to "5!n5!n11!c2!n", "MD" to "2!c18!c",
            "ME" to "3!n13!n2!n", "MK" to "3!n10!c2!n", "MN" to "4!n12!n", "MR" to "5!n5!n11!n2!n",
            "MT" to "4!a5!n18!c", "MU" to "4!a2!n2!n12!n3!n3!a", "NI" to "4!a20!n", "NL" to "4!a10!n",
            "NO" to "4!n6!n1!n", "OM" to "3!n16!c", "PK" to "4!a16!c", "PL" to "8!n16!n", "PS" to "4!a21!c",
            "PT" to "4!n4!n11!n2!n", "QA" to "4!a21!c", "RO" to "4!a16!c", "RS" to "3!n13!n2!n",
            "RU" to "9!n5!n15!c", "SA" to "2!n18!c", "SC" to "4!a2!n2!n16!n3!a", "SD" to "2!n12!n",
            "SE" to "3!n16!n1!n", "SI" to "5!n8!n2!n", "SK" to "4!n6!n10!n", "SM" to "1!a5!n5!n12!c",
            "SO" to "4!n3!n12!n", "ST" to "4!n4!n11!n2!n", "SV" to "4!a20!n", "TL" to "3!n14!n2!n",
            "TN" to "2!n3!n13!n2!n", "TR" to "5!n1!n16!c", "UA" to "6!n19!c", "VA" to "3!n15!n", "VG" to "4!a16!n",
            "XK" to "4!n10!n2!n", "YE" to "4!a4!n18!c",
        )

        private val STRUCTURE_PART = Regex("""(\d+)!([nac])""")

        /**
         * Registry structure indexed by country (first letter * 26 + second) as (length, class)
         * pairs; an array, not a map, so the lookup boxes no key.
         */
        private val REGISTRY_BBAN: Array<IntArray?> = arrayOfNulls<IntArray>(ALPHABET * ALPHABET).also { table ->
            REGISTRY_STRUCTURE.forEach { (country, structure) ->
                table[(country[0] - 'A') * ALPHABET + (country[1] - 'A')] = parseStructure(structure)
            }
        }

        private fun parseStructure(structure: String): IntArray = STRUCTURE_PART.findAll(structure).flatMap { m ->
            val cls = when (m.groupValues[2]) {
                "n" -> CLASS_DIGIT
                "a" -> CLASS_LETTER
                else -> CLASS_ALNUM
            }
            sequenceOf(m.groupValues[1].toInt(), cls)
        }.toList().toIntArray()
    }
}
