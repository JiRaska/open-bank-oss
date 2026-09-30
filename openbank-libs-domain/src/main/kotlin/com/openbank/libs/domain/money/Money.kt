// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.math.BigInteger
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

    /**
     * Rounds under a named [RoundingPolicy] (ADR-0318). The policy's scale must not exceed the
     * currency's fraction digits, since a [Money] cannot hold more; for a finer intermediate
     * scale use [RoundingPolicy.round] on the raw [BigDecimal].
     */
    fun round(policy: RoundingPolicy): Money {
        val target = policy.scaleFor(currency)
        require(target <= currency.defaultFractionDigits) {
            "Policy $policy scale $target exceeds ${currency.code} fraction digits " +
                "${currency.defaultFractionDigits}; " +
                "round the BigDecimal with RoundingPolicy.round instead"
        }
        return Money(amount.setScale(target, policy.mode), currency)
    }

    /**
     * Splits this amount into [n] parts that sum exactly to it (largest-remainder method,
     * ADR-0318). Parts differ by at most one minor unit; earlier parts take the extra units.
     */
    fun split(n: Int): List<Money> {
        require(n > 0) { "split count must be positive, was $n" }
        return allocate(List(n) { BigDecimal.ONE })
    }

    /** [allocate] with integer ratios. */
    fun allocate(vararg ratios: Long): List<Money> = allocate(ratios.map { BigDecimal.valueOf(it) })

    /**
     * Allocates this amount across [ratios] with the largest-remainder method in currency minor
     * units (ADR-0318): each share is first taken rounded DOWN (toward zero), then the leftover
     * minor units go one each to the shares with the largest remainders; equal remainders go to
     * the lower index first. Guarantees: the parts sum exactly to this amount, and every part is
     * strictly within one minor unit of its exact share. A negative amount is allocated as the
     * mirror image of its absolute value. Individual zero ratios are allowed (that part is zero);
     * an empty list, a negative ratio or an all-zero list is rejected.
     */
    fun allocate(ratios: List<BigDecimal>): List<Money> {
        require(ratios.isNotEmpty()) { "ratios must not be empty" }
        require(ratios.none { it.signum() < 0 }) { "ratios must not be negative: $ratios" }
        val ratioScale = ratios.maxOf { maxOf(it.scale(), 0) }
        val weights = ratios.map { it.movePointRight(ratioScale).toBigIntegerExact() }
        val weightSum = weights.fold(BigInteger.ZERO) { acc, w -> acc + w }
        require(weightSum.signum() > 0) { "ratios must not all be zero: $ratios" }

        val digits = currency.defaultFractionDigits
        val totalMinor = amount.movePointRight(digits).toBigIntegerExact()
        val absMinor = totalMinor.abs()
        val floors = ArrayList<BigInteger>(weights.size)
        val remainders = ArrayList<BigInteger>(weights.size)
        for (w in weights) {
            val qr = (absMinor * w).divideAndRemainder(weightSum)
            floors += qr[0]
            remainders += qr[1]
        }
        var leftover = (absMinor - floors.fold(BigInteger.ZERO) { acc, f -> acc + f }).toInt()
        val order = remainders.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it },
        )
        val parts = floors.toMutableList()
        for (i in order) {
            if (leftover == 0) break
            parts[i] = parts[i] + BigInteger.ONE
            leftover--
        }
        return parts.map { minor ->
            val signed = if (totalMinor.signum() < 0) minor.negate() else minor
            Money(BigDecimal(signed, digits), currency)
        }
    }

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
