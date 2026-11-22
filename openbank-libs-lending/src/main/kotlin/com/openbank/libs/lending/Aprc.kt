// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.TreeMap
import kotlin.math.exp
import kotlin.math.ln1p

/**
 * Annual percentage rate of charge — RPSN in Czech, APRC/APR elsewhere (Consumer Credit Directive
 * 2023/2225 Annex III; the same equation as the earlier 2008/48/EC Annex I).
 *
 * The APRC is the rate X that equates, on an annual compounding basis, the present value of what the
 * customer receives with the present value of everything they pay:
 *
 *     Σ  Dₖ / (1 + X)^tₖ   =   Σ  Pⱼ / (1 + X)^sⱼ
 *
 * where D are drawdowns, P are payments (instalments AND charges), and t/s are the intervals in
 * YEARS from the first drawdown. There is no closed form; the directive itself expects a numerical
 * solution, so this solves by bisection.
 *
 * ## Why this is its own file, and why nothing else may compute it
 *
 * The APRC is the number a customer compares lenders on and the one a regulator checks. Under
 * ADR-0269 rule 4 it may only ever come from the server, and within the server it may only come
 * from here: two implementations that disagree in the fourth decimal is a mis-disclosure, not a
 * rounding difference.
 *
 * ## Why fees are an input, not an afterthought
 *
 * The whole point of the APRC is that it prices the total cost of credit, not the interest. A quote
 * that reports the nominal rate as "the APRC" whenever there are no fees works by accident and
 * lies the moment a fee is added, so charges enter the cashflow list explicitly.
 */
object Aprc {

    private val MC = MathContext.DECIMAL128
    private val ONE = BigDecimal.ONE
    private val UPWARD = MathContext(MC.precision, RoundingMode.CEILING)
    private val DOWNWARD = MathContext(MC.precision, RoundingMode.FLOOR)

    /** Bisection bounds: 0% to 10 000% a year. Above that the answer is "this is not a loan". */
    private val LOWER = BigDecimal.ZERO
    private val UPPER = BigDecimal("100")

    private const val MAX_ITERATIONS = 200

    /** Scale of the returned rate as a FRACTION (0.0891 = 8.91%): six places, i.e. four in percent. */
    private const val RATE_SCALE = 6

    /**
     * One cash movement in the APRC equation.
     *
     * [yearsFromStart] is the interval from the first drawdown expressed in years — the directive's
     * own unit — so a monthly instalment k falls at k/12. Fractions are deliberate: rounding periods
     * to whole years would move the answer by more than the disclosure tolerance.
     */
    data class CashFlow(val yearsFromStart: BigDecimal, val amount: BigDecimal)

    /**
     * Solve for the APRC as a fraction, e.g. `0.089100` for 8.91%.
     *
     * [advances] are what the customer receives (drawdowns), [payments] everything they pay back,
     * including charges. Both must be positive amounts; the sign convention lives here rather than
     * in the caller, because a caller that passes a negative payment silently inverts the equation.
     *
     * Returns null when a unique positive rate in the searched range cannot be computed: no
     * positive root, multiple candidates, uncertain arithmetic or exhausted bounded work. This also
     * includes zero-cost loans. Null must remain absent rather than be rendered as 0%.
     *
     * The returned fraction has six HALF_UP decimal places. Floating-point exponential evaluation
     * does not provide a certified exact-root rounding or root-count guarantee near ill-conditioned
     * roots; ambiguous numerical signs are left unresolved rather than treated as a zero.
     */
    fun solve(advances: List<CashFlow>, payments: List<CashFlow>): BigDecimal? {
        require(advances.isNotEmpty()) { "at least one advance is required" }
        require(payments.isNotEmpty()) { "at least one payment is required" }
        require(advances.all { it.amount > BigDecimal.ZERO }) { "advances must be positive" }
        require(payments.all { it.amount > BigDecimal.ZERO }) { "payments must be positive" }
        require((advances + payments).all { it.yearsFromStart >= BigDecimal.ZERO }) {
            "cash flows must not precede the first drawdown"
        }

        return try {
            search(groupedTerms(advances, payments), advances, payments)
        } catch (_: ArithmeticException) {
            // Valid input shape may still exceed finite arithmetic; that is unresolved, not zero.
            null
        }
    }

    private fun search(terms: List<Term>, advances: List<CashFlow>, payments: List<CashFlow>): BigDecimal? {
        if (terms.isEmpty()) return null
        exactOneYear(terms)?.let { return it }
        // The rational shape is fully decided here, including zero/out-of-range roots.
        if (terms.size == 2 && terms[0].time.signum() == 0 && terms[1].time.compareTo(ONE) == 0) return null

        val work = Work()
        val exactUpper = exactBalanceAt(terms, UPPER, work) == true
        if (exactUpper && variations(terms) == 1) return UPPER.setScale(RATE_SCALE)
        if (variations(terms) == 1) return ScalarBalance(terms, advances, payments, work).refine(LOWER, UPPER)
        val roots = discover(terms, exactUpper, work) ?: return null
        return ScalarBalance(terms, advances, payments, work).fromRoots(roots)
    }

    private fun discover(terms: List<Term>, exactUpper: Boolean, work: Work): List<Bracket>? {
        var level = 0
        var current = terms
        // Only the current derivative is retained. No recursive JVM stack or quadratic term cache.
        while (variations(current) > 1) {
            current = derivative(current, work) ?: return null
            level++
        }
        var roots = isolate(current, emptyList(), level == 0, exactUpper, work) ?: return null
        while (level > 0) {
            level--
            current = terms
            repeat(level) { current = derivative(current, work) ?: return null }
            roots = isolate(current, roots, level == 0, exactUpper, work) ?: return null
        }
        return roots
    }

    private data class Term(val time: BigDecimal, val amount: BigDecimal, val error: BigDecimal = BigDecimal.ZERO)
    private data class Bracket(val low: Double, val high: Double, val upperEndpoint: Boolean = false)

    /** Local outward arithmetic only; every nonfinite enclosure is unresolved. */
    private data class Range(val low: Double, val high: Double) {
        val sign: Int? get() = when {
            low > 0.0 -> 1
            high < 0.0 -> -1
            else -> null
        }
        fun plus(other: Range): Range? = Enclosures.finiteRange(
            Math.nextDown(low + other.low),
            Math.nextUp(high + other.high),
        )
        fun times(other: Range): Range? {
            val values = doubleArrayOf(low * other.low, low * other.high, high * other.low, high * other.high)
            if (values.any { !it.isFinite() }) return null
            return Enclosures.finiteRange(Math.nextDown(values.min()), Math.nextUp(values.max()))
        }
        fun negative(): Range = Range(-high, -low)
        fun reciprocal(): Range? = if (low > 0.0) {
            Enclosures.finiteRange(Math.nextDown(1.0 / high), Math.nextUp(1.0 / low))
        } else {
            null
        }
    }

    private object Enclosures {
        fun finiteRange(low: Double, high: Double): Range? =
            if (low.isFinite() && high.isFinite() && low <= high) Range(low, high) else null

        fun decimalRange(value: BigDecimal): Range? {
            val converted = value.toDouble()
            if (!converted.isFinite()) return null
            return if (BigDecimal(converted).compareTo(value) == 0) {
                Range(converted, converted)
            } else {
                finiteRange(Math.nextDown(converted), Math.nextUp(converted))
            }
        }

        // Two returned-result ulps cover the documented one exact-result ulp across binade changes.
        fun functionRange(low: Double, high: Double): Range? {
            if (!low.isFinite() || !high.isFinite()) return null
            return finiteRange(
                Math.nextDown(low - 2 * Math.ulp(low)).coerceAtLeast(0.0),
                Math.nextUp(high + 2 * Math.ulp(high)),
            )
        }

        fun upperCoordinate(work: Work): Range? {
            if (!work.take()) return null
            val value = ln1p(UPPER.toDouble())
            return functionRange(value, value)
        }

        fun rateRange(at: Double, work: Work): Range? {
            if (!work.take()) return null
            val exponential = functionRange(exp(at), exp(at)) ?: return null
            return exponential.plus(Range(-1.0, -1.0))
        }
    }

    private class Work {
        private var remaining = MAX_ITERATIONS
        fun take(): Boolean {
            if (remaining == 0) return false
            remaining--
            return true
        }
    }

    private fun groupedTerms(advances: List<CashFlow>, payments: List<CashFlow>): List<Term> {
        val grouped = TreeMap<BigDecimal, BigDecimal>()
        advances.forEach { grouped.merge(it.yearsFromStart, it.amount.negate(), BigDecimal::add) }
        payments.forEach { grouped.merge(it.yearsFromStart, it.amount, BigDecimal::add) }
        val terms = grouped.entries.filter { it.value.signum() != 0 }
        val first = terms.firstOrNull()?.key ?: return emptyList()
        // Dividing by the common positive discount factor preserves every positive-rate root.
        return terms.map { Term(it.key.subtract(first), it.value) }
    }

    private fun exactOneYear(terms: List<Term>): BigDecimal? {
        if (terms.size != 2 || terms[0].time.signum() != 0 || terms[1].time.compareTo(ONE) != 0) return null
        var denominator = terms[0].amount
        var numerator = terms[1].amount.negate().subtract(denominator)
        if (denominator.signum() < 0) {
            denominator = denominator.negate()
            numerator = numerator.negate()
        }
        if (numerator.signum() <= 0 || numerator > denominator.multiply(UPPER)) return null
        // Divide only once at the final output scale: an exact half boundary cannot be lost in MC.
        return numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP)
    }

    private fun variations(terms: List<Term>): Int =
        terms.zipWithNext().count { (a, b) -> a.amount.signum() != b.amount.signum() }

    private fun derivative(terms: List<Term>, work: Work): List<Term>? {
        if (!work.take()) return null
        if (terms.size < 2) return emptyList()
        val first = terms[1].time
        return terms.drop(1).map {
            val amount = it.amount.multiply(it.time, MC).negate()
            val exponent = amount.precision().toLong() - amount.scale() - MC.precision
            if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            val rounding = ONE.scaleByPowerOfTen(exponent.toInt())
            val error = it.error.multiply(it.time.abs(), UPWARD).add(rounding, UPWARD)
            Term(it.time.subtract(first), amount, error)
        }
    }

    private class Evaluator(terms: List<Term>, private val work: Work) {
        private val active = work.take()
        private val scale = terms.maxOf { it.amount.abs() }
        private val numeric =
            if (!active) {
                emptyList()
            } else {
                terms.map { term ->
                    val lower = term.amount.subtract(term.error).divide(scale, DOWNWARD)
                    val upper = term.amount.add(term.error).divide(scale, UPWARD)
                    val low = Enclosures.decimalRange(lower)?.low
                    val high = Enclosures.decimalRange(upper)?.high
                    val coefficient = if (low != null && high != null) Enclosures.finiteRange(low, high) else null
                    Enclosures.decimalRange(term.time) to coefficient
                }
            }
        private val usable = active &&
            numeric.all { (time, amount) ->
                time != null && time.low >= 0.0 && amount?.sign != null
            }

        fun value(at: Range): Range? {
            if (!work.take() || !usable || at.low < 0.0) return null
            var total = Range(0.0, 0.0)
            for ((time, amount) in numeric) {
                val exponent = time!!.times(at)?.negative() ?: return null
                val factor = Enclosures.functionRange(exp(exponent.low), exp(exponent.high)) ?: return null
                total = total.plus(amount!!.times(factor) ?: return null) ?: return null
            }
            return total
        }

        fun atRate(rate: BigDecimal): Range? {
            if (!work.take() || !usable) return null
            val rawBase = Enclosures.decimalRange(ONE.add(rate)) ?: return null
            val base = Range(rawBase.low.coerceAtLeast(1.0), rawBase.high)
            var total = Range(0.0, 0.0)
            for ((time, amount) in numeric) {
                val factor = Enclosures.functionRange(
                    Math.pow(base.low, time!!.low),
                    Math.pow(base.high, time.high),
                ) ?: return null
                val weight = factor.reciprocal() ?: return null
                total = total.plus(amount!!.times(weight) ?: return null) ?: return null
            }
            return total
        }
    }

    private fun isolate(
        terms: List<Term>,
        critical: List<Bracket>,
        output: Boolean,
        exactUpper: Boolean,
        work: Work,
    ): List<Bracket>? {
        if (terms.isEmpty() || variations(terms) == 0) return emptyList()
        return Isolation(terms, critical, output, exactUpper, work).run()
    }

    /** State for one monotone partition pass, never a retained recursive derivative chain. */
    private class Isolation(
        private val terms: List<Term>,
        private val critical: List<Bracket>,
        private val output: Boolean,
        exactUpper: Boolean,
        private val work: Work,
    ) {
        private val evaluator = Evaluator(terms, work)
        private val roots = mutableListOf<Bracket>()
        private var low = LOWER.toDouble()
        private lateinit var left: Range
        private var excludedZero = false
        private val endpoint = output && exactUpper

        fun run(): List<Bracket>? {
            val upper = Enclosures.upperCoordinate(work) ?: return null
            if (!initializeLower()) return null
            // A derivative root overlapping the true upper-coordinate band remains unresolved.
            if (endpoint && critical.any { it.high >= upper.low }) return null
            val turns = if (endpoint) critical else critical + Bracket(upper.high, upper.high)
            for (turn in turns) {
                if (!visit(turn, upper)) return null
            }
            if (endpoint) {
                if (left.sign == null || excludedZero) return null
                roots += Bracket(upper.low, upper.high, upperEndpoint = true)
            }
            return roots
        }

        private fun initializeLower(): Boolean {
            left = evaluator.value(Range(low, low)) ?: return false
            if (output && left.sign == null && terms.all { it.error.signum() == 0 }) {
                if (!excludeLowerZero()) return false
                excludedZero = true
            }
            return true
        }

        private fun excludeLowerZero(): Boolean {
            var atZero = BigDecimal.ZERO
            for (term in terms) {
                if (!work.take()) return false
                atZero = atZero.add(term.amount)
            }
            return atZero.signum() == 0
        }

        private fun visit(turn: Bracket, upper: Range): Boolean {
            if (turn.low < low || turn.high > upper.high) return false
            val right = evaluator.value(Range(turn.low, turn.low)) ?: return false
            if (right.sign == null) return false
            if (!inspectPiece(right, turn.low)) return false
            left = afterCritical(turn, right) ?: return false
            low = turn.high
            return true
        }

        private fun inspectPiece(right: Range, to: Double): Boolean {
            if (excludedZero) {
                // The excluded exact left root cannot be another root on this monotone piece.
                excludedZero = false
            } else {
                if (left.sign == null) return false
                if (left.sign != right.sign) {
                    roots += if (output) Bracket(low, to) else refineCritical(low, to, left.sign!!) ?: return false
                }
            }
            return true
        }

        private fun afterCritical(turn: Bracket, right: Range): Range? {
            if (turn.high > turn.low) {
                val neighborhood = evaluator.value(Range(turn.low, turn.high)) ?: return null
                if (neighborhood.sign == null || neighborhood.sign != right.sign) return null
                return evaluator.value(Range(turn.high, turn.high))
            }
            return right
        }

        private fun refineCritical(from: Double, to: Double, initialSign: Int): Bracket? {
            var low = from
            var high = to
            while (true) {
                val middle = low + (high - low) / 2
                if (middle == low || middle == high) return Bracket(low, high)
                val value = evaluator.value(Range(middle, middle)) ?: return null
                val sign = value.sign ?: return Bracket(low, high)
                if (sign == initialSign) low = middle else high = middle
            }
        }
    }

    /** Original-list scalar valuation and its bounded refinement, separate from bracket discovery. */
    private class ScalarBalance(
        private val terms: List<Term>,
        private val advances: List<CashFlow>,
        private val payments: List<CashFlow>,
        private val work: Work,
    ) {
        fun fromRoots(roots: List<Bracket>): BigDecimal? {
            if (roots.size != 1) return null
            if (roots.single().upperEndpoint) return UPPER.setScale(RATE_SCALE)
            val low = Enclosures.rateRange(roots.single().low, work)?.low ?: return null
            val high = Enclosures.rateRange(roots.single().high, work)?.high ?: return null
            val from = BigDecimal(low).max(LOWER)
            val to = BigDecimal(high).min(UPPER)
            if (from >= to) return null
            return refine(from, to)
        }

        fun refine(from: BigDecimal, to: BigDecimal): BigDecimal? {
            val guard = Evaluator(terms, work)
            var low = from
            var high = to
            val initial = sign(low, guard) ?: return null
            val last = sign(high, guard) ?: return null
            if (initial == 0) return low.takeIf { it > LOWER }?.setScale(RATE_SCALE, RoundingMode.HALF_UP)
            if (last == 0) return high.setScale(RATE_SCALE, RoundingMode.HALF_UP)
            if (initial == last) return null
            while (true) {
                val rounded = low.setScale(RATE_SCALE, RoundingMode.HALF_UP)
                if (rounded == high.setScale(RATE_SCALE, RoundingMode.HALF_UP)) return rounded
                val middle = low.add(high, MC).divide(BigDecimal(2), MC)
                if (middle == low || middle == high) return null
                val value = sign(middle, guard) ?: return null
                if (value == 0) return middle.setScale(RATE_SCALE, RoundingMode.HALF_UP)
                if (value == initial) low = middle else high = middle
            }
        }

        private fun sign(at: BigDecimal, guard: Evaluator): Int? {
            if (!work.take()) return null
            val value = presentValue(payments, at).subtract(presentValue(advances, at), MC)
            val enclosure = guard.atRate(at) ?: return null
            if (enclosure.sign == null) {
                return if (value.signum() == 0 && exactBalanceAt(terms, at, work) == true) 0 else null
            }
            return value.signum().takeIf { it == enclosure.sign }
        }

        private fun presentValue(flows: List<CashFlow>, rate: BigDecimal): BigDecimal =
            flows.fold(BigDecimal.ZERO) { acc, flow ->
                acc.add(flow.amount.divide(discountFactor(rate, flow.yearsFromStart), MC), MC)
            }

        private fun discountFactor(rate: BigDecimal, years: BigDecimal): BigDecimal {
            if (years.signum() == 0) return ONE
            val factor = Math.pow(ONE.add(rate).toDouble(), years.toDouble())
            if (!factor.isFinite() || factor == 0.0) {
                throw ArithmeticException("discount factor is not finite and positive")
            }
            return BigDecimal(factor, MC)
        }
    }

    private fun exactBalanceAt(terms: List<Term>, rate: BigDecimal, work: Work): Boolean? {
        if (terms.any { it.error.signum() != 0 }) return null
        val base = ONE.add(rate)
        val powers = terms.map {
            if (
                it.time.stripTrailingZeros().scale() > 0 ||
                it.time > BigDecimal(MC.precision / base.precision())
            ) {
                return null
            }
            it.time.intValueExact()
        }
        val highest = powers.maxOrNull() ?: return null
        var polynomial = BigDecimal.ZERO
        for ((index, term) in terms.withIndex()) {
            if (!work.take()) return null
            polynomial = polynomial.add(term.amount.multiply(base.pow(highest - powers[index])))
        }
        return polynomial.signum() == 0
    }
}
