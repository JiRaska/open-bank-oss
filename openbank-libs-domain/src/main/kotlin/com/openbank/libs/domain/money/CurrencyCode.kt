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
 */
class CurrencyCode private constructor(val code: String, val defaultFractionDigits: Int) {

    override fun equals(other: Any?): Boolean = this === other || (other is CurrencyCode && code == other.code)

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        private const val CODE_LENGTH = 3
        private const val ECHO_LIMIT = 16

        val CZK = of("CZK")
        val EUR = of("EUR")
        val USD = of("USD")
        val GBP = of("GBP")
        val CHF = of("CHF")

        /** Same as [of]; keeps the constructor-style call form `CurrencyCode("CZK")`. */
        operator fun invoke(code: String): CurrencyCode = of(code)

        /**
         * Validates and normalises [code]: exactly three ASCII letters in either case, known to
         * ISO 4217, with a defined minor unit. Throws [IllegalArgumentException] otherwise.
         */
        fun of(code: String): CurrencyCode {
            require(code.length == CODE_LENGTH && code.all { it in 'A'..'Z' || it in 'a'..'z' }) {
                "Currency code must be 3 letters (ISO 4217): '${code.take(ECHO_LIMIT)}'"
            }
            val normalised = code.uppercase(Locale.ROOT)
            val currency = try {
                Currency.getInstance(normalised)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Unknown ISO 4217 currency code: $normalised", e)
            }
            require(currency.defaultFractionDigits >= 0) {
                "Currency $normalised has no minor unit (ISO 4217) and cannot denominate a monetary amount"
            }
            return CurrencyCode(normalised, currency.defaultFractionDigits)
        }
    }
}
