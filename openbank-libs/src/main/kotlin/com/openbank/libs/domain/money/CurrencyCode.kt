// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.money

import java.util.Currency

data class CurrencyCode(val code: String) {
    val defaultFractionDigits: Int = Currency.getInstance(code).defaultFractionDigits

    init {
        require(code.length == 3) { "Currency code must be 3 characters (ISO 4217): $code" }
        runCatching { Currency.getInstance(code) }
            .onFailure { throw IllegalArgumentException("Unknown ISO 4217 currency code: $code") }
    }

    override fun toString(): String = code

    companion object {
        val CZK = CurrencyCode("CZK")
        val EUR = CurrencyCode("EUR")
        val USD = CurrencyCode("USD")
        val GBP = CurrencyCode("GBP")
        val CHF = CurrencyCode("CHF")

        fun of(code: String): CurrencyCode = CurrencyCode(code.uppercase())
    }
}
