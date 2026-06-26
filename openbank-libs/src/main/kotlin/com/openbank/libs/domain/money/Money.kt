// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.domain.money

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.math.RoundingMode

data class Money @JsonCreator constructor(
    @JsonProperty("amount") val amount: BigDecimal,
    @JsonProperty("currency") val currency: CurrencyCode,
) {
    init {
        require(amount.scale() <= currency.defaultFractionDigits) {
            "Amount scale ${amount.scale()} exceeds currency ${currency.code} fraction digits ${currency.defaultFractionDigits}"
        }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount - other.amount, currency)
    }

    operator fun unaryMinus(): Money = Money(amount.negate(), currency)

    fun isPositive(): Boolean = amount > BigDecimal.ZERO
    fun isNegative(): Boolean = amount < BigDecimal.ZERO
    fun isZero(): Boolean = amount.compareTo(BigDecimal.ZERO) == 0
    fun isNonNegative(): Boolean = amount >= BigDecimal.ZERO

    fun abs(): Money = Money(amount.abs(), currency)

    fun scale(): Money = Money(
        amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_EVEN),
        currency,
    )

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Cannot operate on different currencies: ${currency.code} and ${other.currency.code}"
        }
    }

    override fun toString(): String = "${amount.toPlainString()} ${currency.code}"

    companion object {
        fun of(amount: BigDecimal, currencyCode: String): Money = Money(amount, CurrencyCode.of(currencyCode))

        fun of(amount: String, currencyCode: String): Money = Money(BigDecimal(amount), CurrencyCode.of(currencyCode))

        fun zero(currencyCode: String): Money = Money(
            BigDecimal.ZERO.setScale(CurrencyCode.of(currencyCode).defaultFractionDigits),
            CurrencyCode.of(currencyCode),
        )
    }
}
