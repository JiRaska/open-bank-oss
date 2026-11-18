// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import java.util.Currency
import java.util.Locale

/**
 * An ISO 4217 alphabetic currency code that can denominate [Money].
 *
 * Every instance is built by [of] (the `CurrencyCode("eur")` call form is the same function), so
 * there is exactly one place where the code is validated and upper-cased: two instances are equal
 * iff they name the same currency, whatever case the caller typed.
 *
 * **Currencies without a minor unit are rejected.** ISO 4217 lists codes such as `XAU`, `XDR` and
 * `XXX` for which the JDK reports `defaultFractionDigits == -1`. Such a code has no scale a
 * monetary amount could be held at, and a `-1` would reach every caller that writes
 * `setScale(currency.defaultFractionDigits)`. [defaultFractionDigits] is therefore always `>= 0`.
 *
 * **Withdrawn codes are rejected.** `java.util.Currency` still knows currencies that ISO 4217
 * has withdrawn (`DEM`, `FRF`, `HRK`, `BGN` after 2026-01-01, and about fifty more), so on its own
 * it accepts money in a currency that no longer exists. A code must also be on ISO 4217 List One
 * ([ACTIVE]). The identifier differential test checks that list against an independent copy
 * (pycountry / Debian iso-codes) over every three-letter string (#16748).
 */
class CurrencyCode private constructor(val code: String, val defaultFractionDigits: Int) {

    override fun equals(other: Any?): Boolean = this === other || (other is CurrencyCode && code == other.code)

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        private const val CODE_LENGTH = 3
        private const val ECHO_LIMIT = 16

        /** ISO 4217 List One (active codes), as of the 2026 amendments. */
        private val ACTIVE: Set<String> = setOf(
            "AED", "AFN", "ALL", "AMD", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT", "BHD", "BIF", "BMD",
            "BND", "BOB", "BOV", "BRL", "BSD", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF", "CHE", "CHF", "CHW", "CLF",
            "CLP", "CNY", "COP", "COU", "CRC", "CUP", "CVE", "CZK", "DJF", "DKK", "DOP", "DZD", "EGP", "ERN", "ETB",
            "EUR", "FJD", "FKP", "GBP", "GEL", "GHS", "GIP", "GMD", "GNF", "GTQ", "GYD", "HKD", "HNL", "HTG", "HUF",
            "IDR", "ILS", "INR", "IQD", "IRR", "ISK", "JMD", "JOD", "JPY", "KES", "KGS", "KHR", "KMF", "KPW", "KRW",
            "KWD", "KYD", "KZT", "LAK", "LBP", "LKR", "LRD", "LSL", "LYD", "MAD", "MDL", "MGA", "MKD", "MMK", "MNT",
            "MOP", "MRU", "MUR", "MVR", "MWK", "MXN", "MXV", "MYR", "MZN", "NAD", "NGN", "NIO", "NOK", "NPR", "NZD",
            "OMR", "PAB", "PEN", "PGK", "PHP", "PKR", "PLN", "PYG", "QAR", "RON", "RSD", "RUB", "RWF", "SAR", "SBD",
            "SCR", "SDG", "SEK", "SGD", "SHP", "SLE", "SOS", "SRD", "SSP", "STN", "SVC", "SYP", "SZL", "THB", "TJS",
            "TMT", "TND", "TOP", "TRY", "TTD", "TWD", "TZS", "UAH", "UGX", "USD", "USN", "UYI", "UYU", "UYW", "UZS",
            "VED", "VES", "VND", "VUV", "WST", "XAD", "XAF", "XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XCD", "XCG",
            "XDR", "XOF", "XPD", "XPF", "XPT", "XSU", "XTS", "XUA", "XXX", "YER", "ZAR", "ZMW", "ZWG",
        )

        val CZK = of("CZK")
        val EUR = of("EUR")
        val USD = of("USD")
        val GBP = of("GBP")
        val CHF = of("CHF")

        /** Same as [of]; keeps the constructor-style call form `CurrencyCode("CZK")`. */
        operator fun invoke(code: String): CurrencyCode = of(code)

        /**
         * Validates and normalises [code]: exactly three ASCII letters in either case, known to
         * ISO 4217, with a defined minor unit. Throws [InvalidMoneyException]
         * ([InvalidMoneyReason.CURRENCY_UNSUPPORTED], an [IllegalArgumentException]) otherwise.
         */
        fun of(code: String): CurrencyCode {
            if (code.length != CODE_LENGTH || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) {
                throw unsupported("Currency code must be 3 letters (ISO 4217): '${code.take(ECHO_LIMIT)}'")
            }
            val normalised = code.uppercase(Locale.ROOT)
            val currency = isoCurrency(normalised)
            if (currency.defaultFractionDigits < 0) {
                throw unsupported(
                    "Currency $normalised has no minor unit (ISO 4217) and cannot denominate a monetary amount",
                )
            }
            return CurrencyCode(normalised, currency.defaultFractionDigits)
        }

        private fun isoCurrency(normalised: String): Currency = try {
            require(normalised in ACTIVE)
            Currency.getInstance(normalised)
        } catch (e: IllegalArgumentException) {
            throw unsupported("Unknown ISO 4217 currency code: $normalised", e)
        }

        private fun unsupported(message: String, cause: Throwable? = null) = InvalidMoneyException(
            InvalidMoneyReason.CURRENCY_UNSUPPORTED,
            message,
            clientMessage = "Currency must be an ISO 4217 code with a minor unit",
            cause = cause,
        )
    }
}
