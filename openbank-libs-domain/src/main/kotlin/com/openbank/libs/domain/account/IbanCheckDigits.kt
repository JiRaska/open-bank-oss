// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.account

/**
 * ISO 13616 / ISO 7064 MOD 97-10 check-digit computation, for code that BUILDS an IBAN from a
 * country and a BBAN. Validation stays in [Iban.isValid]; this is the one generator, so nobody
 * re-implements the letter expansion next to their own generator.
 */
object IbanCheckDigits {
    private const val MODULUS = 97
    private const val BASE = 98
    private const val LETTER_OFFSET = 10
    private const val RADIX = 10

    /** The two check digits for [countryCode] + [bban]: 98 − ((bban + country + "00") mod 97). */
    fun compute(countryCode: String, bban: String): String {
        require(
            countryCode.length == 2 &&
                countryCode.all {
                    it in 'A'..'Z'
                },
        ) { "country must be two upper-case letters" }
        require(bban.isNotEmpty() && bban.all { it in '0'..'9' || it in 'A'..'Z' }) { "BBAN must be [A-Z0-9]+" }
        var remainder = 0
        for (c in bban + countryCode + "00") {
            val digits = if (c.isDigit()) c.toString() else (c - 'A' + LETTER_OFFSET).toString()
            for (d in digits) remainder = (remainder * RADIX + (d - '0')) % MODULUS
        }
        return (BASE - remainder).toString().padStart(2, '0')
    }

    /** Builds and validates the IBAN; throws if the result is not a valid [Iban] for its country. */
    fun build(countryCode: String, bban: String): Iban = Iban.of(countryCode + compute(countryCode, bban) + bban)
}
