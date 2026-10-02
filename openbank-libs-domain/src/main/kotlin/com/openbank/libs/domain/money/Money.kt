// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.money

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * A monetary amount in one currency, held in a single canonical representation.
 *
 * **Canonical scale.** [amount] always has exactly the currency's number of fraction digits
 * (`EUR` 2, `JPY` 0, `KWD` 3). A shorter or negative scale is padded (`1`, `1.0` and `1E+0` EUR
 * all become `1.00`), and trailing zeros beyond the currency scale are dropped (`1.000` EUR is
 * `1.00`). An amount that would need ROUNDING to fit (`1.005` EUR) is rejected: construction never
 * changes a value, so rounding stays an explicit decision ([round], ADR-0318). Because the
 * representation is unique, numerically equal amounts are `==`, hash equally, and
 * [compareTo] is consistent with [equals].
 *
 * **Bounded magnitude.** At most [MAX_INTEGER_DIGITS] integer digits and an input scale of at
 * most [MAX_INPUT_SCALE] are accepted. The bound is checked first, from `precision()` and
 * `scale()` alone, before any arithmetic touches the value. [MAX_INTEGER_DIGITS] is the widest
 * monetary column in the fleet (`NUMERIC(23,4)`); [MAX_INPUT_SCALE] covers the widest fractional
 * column (`NUMERIC(38,18)`), so a value read back from any of them can be offered for
 * normalisation. Arithmetic whose result leaves the range throws like any other construction.
 *
 * This type carries no serialisation annotations (ADR-0122). Its JSON form is defined by
 * `MoneyJacksonModule` in `openbank-libs-runtime`.
 */
class Money private constructor(val amount: BigDecimal, val currency: CurrencyCode) : Comparable<Money> {

    init {
        // Backstop for the one path that bypasses [invoke]: inside this class `Money(a, c)`
        // resolves to this constructor, not to the normalising factory.
        check(amount.scale() == currency.defaultFractionDigits) {
            "Money must be built through Money.invoke/of: scale ${amount.scale()} is not canonical for ${currency.code}"
        }
    }

    operator fun component1(): BigDecimal = amount

    operator fun component2(): CurrencyCode = currency

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return invoke(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return invoke(amount - other.amount, currency)
    }

    operator fun unaryMinus(): Money = invoke(amount.negate(), currency)

    fun isPositive(): Boolean = amount > BigDecimal.ZERO
    fun isNegative(): Boolean = amount < BigDecimal.ZERO
    fun isZero(): Boolean = amount.compareTo(BigDecimal.ZERO) == 0
    fun isNonNegative(): Boolean = amount >= BigDecimal.ZERO

    fun abs(): Money = invoke(amount.abs(), currency)

    /**
     * Returns this amount. A [Money] is always held at its currency's scale, so there is nothing
     * left to scale and nothing to round; kept for callers written before that was an invariant.
     */
    fun scale(): Money = this

    /**
     * Rounds under a named [RoundingPolicy] (ADR-0318). The policy's scale must not exceed the
     * currency's fraction digits, since a [Money] cannot hold more; for a finer intermediate
     * scale use [RoundingPolicy.round] on the raw [BigDecimal]. A policy coarser than the currency
     * scale rounds the VALUE; the result is still held at the currency scale.
     */
    fun round(policy: RoundingPolicy): Money {
        val target = policy.scaleFor(currency)
        require(target <= currency.defaultFractionDigits) {
            "Policy $policy scale $target exceeds ${currency.code} fraction digits " +
                "${currency.defaultFractionDigits}; " +
                "round the BigDecimal with RoundingPolicy.round instead"
        }
        return invoke(amount.setScale(target, policy.mode), currency)
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
            invoke(BigDecimal(signed, digits), currency)
        }
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Cannot operate on different currencies: ${currency.code} and ${other.currency.code}"
        }
    }

    /**
     * Orders two amounts of the SAME currency; consistent with [equals]. Comparing across
     * currencies has no meaning without a rate and throws, exactly like [plus].
     */
    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Money && currency == other.currency && amount == other.amount)

    override fun hashCode(): Int = HASH_PRIME * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "${amount.toPlainString()} ${currency.code}"

    companion object {
        /** Widest integer part a monetary column in the fleet can store (`NUMERIC(23,4)`). */
        const val MAX_INTEGER_DIGITS = 19

        /** Widest fractional part an amount may arrive with before normalisation (`NUMERIC(38,18)`). */
        const val MAX_INPUT_SCALE = 18

        /** Longest decimal text [parseAmount] will hand to [BigDecimal]: sign, digits, point, exponent. */
        const val MAX_TEXT_LENGTH = 64

        private const val HASH_PRIME = 31

        /** The only way to obtain a [Money]: validates the range, then normalises the scale. */
        operator fun invoke(amount: BigDecimal, currency: CurrencyCode): Money =
            Money(canonical(amount, currency), currency)

        fun of(amount: BigDecimal, currencyCode: String): Money = invoke(amount, CurrencyCode.of(currencyCode))

        fun of(amount: String, currencyCode: String): Money = invoke(parseAmount(amount), CurrencyCode.of(currencyCode))

        fun zero(currencyCode: String): Money = invoke(BigDecimal.ZERO, CurrencyCode.of(currencyCode))

        /**
         * Parses decimal text into a [BigDecimal] that is safe to normalise: the length is bounded
         * before parsing and the magnitude before anything else looks at the digits. Shared with
         * the JSON reader so both entry points apply one rule.
         */
        fun parseAmount(text: String): BigDecimal {
            require(text.length <= MAX_TEXT_LENGTH) {
                "Amount text is ${text.length} characters; at most $MAX_TEXT_LENGTH are accepted"
            }
            val parsed = try {
                BigDecimal(text)
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Amount is not a decimal number", e)
            }
            requireInRange(parsed)
            return parsed
        }

        /**
         * Range check on `precision()` and `scale()` only — both are stored fields, so this costs
         * the same for every input and never expands the digits of an out-of-range value. Long
         * arithmetic, because `precision - scale` overflows an Int at the extremes of the scale.
         */
        private fun requireInRange(amount: BigDecimal) {
            val scale = amount.scale()
            val integerDigits = amount.precision().toLong() - scale.toLong()
            require(integerDigits <= MAX_INTEGER_DIGITS) {
                "Amount has $integerDigits integer digits; at most $MAX_INTEGER_DIGITS are supported"
            }
            require(scale <= MAX_INPUT_SCALE) {
                "Amount scale $scale exceeds the supported maximum of $MAX_INPUT_SCALE"
            }
        }

        private fun canonical(amount: BigDecimal, currency: CurrencyCode): BigDecimal {
            requireInRange(amount)
            val digits = currency.defaultFractionDigits
            if (amount.scale() == digits) return amount
            return try {
                amount.setScale(digits, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException(
                    "Amount scale ${amount.scale()} exceeds currency ${currency.code} fraction digits $digits",
                    e,
                )
            }
        }
    }
}
