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
 * **Withdrawn currencies are rejected.** `java.util.Currency` keeps every code ISO 4217 ever
 * published (List Three, "historic denominations") so it can parse old data: `DEM`, `FRF`, `ITL`,
 * `HRK`, `SKK` all resolve with a minor unit. None of them can denominate a payment today, so an
 * amount in one is a data error, not a currency. [WITHDRAWN] is that list as the JDK knows it.
 */
class CurrencyCode private constructor(val code: String, val defaultFractionDigits: Int) {

    override fun equals(other: Any?): Boolean = this === other || (other is CurrencyCode && code == other.code)

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = code

    companion object {
        private const val CODE_LENGTH = 3
        private const val ECHO_LIMIT = 16

        /**
         * ISO 4217 List Three codes the JDK still resolves with a minor unit. Must stay a literal
         * table: no JDK API distinguishes an active code from a historic one. Declared before the
         * constants below, which call [of] during companion initialisation.
         */
        private val WITHDRAWN: Set<String> = setOf(
            "ADP", "AFA", "ANG", "ATS", "AYM", "AZM", "BEF", "BGL", "BYB", "BYR", "CSD", "CYP", "DEM",
            "EEK", "ESP", "FIM", "FRF", "GHC", "GRD", "GWP", "HRK", "IEP", "ITL", "LTL", "LUF", "LVL",
            "MGF", "MRO", "MTL", "MZM", "NLG", "PTE", "ROL", "RUR", "SDD", "SIT", "SKK", "SLL", "SRG",
            "STD", "TMM", "TPE", "TRL", "USS", "VEB", "VEF", "YUM", "ZMK", "ZWD", "ZWL", "ZWN", "ZWR",
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

        /**
         * True iff [code] is already the canonical form [of] would produce: three upper-case
         * letters naming an active ISO 4217 currency with a minor unit. For validators that store
         * the string as-is and compare it by equality, where `eur` must not pass as `EUR`.
         */
        fun isCanonical(code: String): Boolean = runCatching { of(code).code == code }.getOrDefault(false)

        private fun isoCurrency(normalised: String): Currency = try {
            require(normalised !in WITHDRAWN) {
                "Currency $normalised is withdrawn from ISO 4217 and cannot denominate a payment"
            }
            Currency.getInstance(normalised)
        } catch (e: IllegalArgumentException) {
            val reason = if (normalised in
                WITHDRAWN
            ) {
                e.message.orEmpty()
            } else {
                "Unknown ISO 4217 currency code: $normalised"
            }
            throw unsupported(reason, e)
        }

        private fun unsupported(message: String, cause: Throwable? = null) = InvalidMoneyException(
            InvalidMoneyReason.CURRENCY_UNSUPPORTED,
            message,
            clientMessage = "Currency must be an ISO 4217 code with a minor unit",
            cause = cause,
        )
    }
}
