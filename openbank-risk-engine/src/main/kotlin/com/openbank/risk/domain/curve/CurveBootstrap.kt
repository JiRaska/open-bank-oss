// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.calendar.DateRoll
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.pricing.LegPricer
import com.openbank.libs.finance.product.DealTerms
import com.openbank.libs.finance.product.FixedLegTerms
import com.openbank.libs.finance.product.FloatingLegTerms
import com.openbank.libs.finance.product.LegTemplates
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.ScheduleGenerator
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period

/**
 * A money-market tenor: `ON` (one day), or `<n>D`, `<n>W`, `<n>M`, `<n>Y`, up to one year — the
 * range a deposit quote covers. FRAs and swaps carry their own tenor shapes ([FraQuote], [SwapQuote]).
 */
data class Tenor(val code: String, private val period: Period) {
    fun endDate(from: LocalDate): LocalDate = from.plus(period)

    companion object {
        private val PATTERN = Regex("^([1-9][0-9]{0,2})([DWMY])$")
        private const val MAX_DAYS = 7
        private const val MAX_WEEKS = 52
        private const val MAX_MONTHS = 12
        private const val MAX_YEARS = 1

        fun parse(raw: String): Tenor {
            val code = raw.trim().uppercase()
            if (code == "ON") return Tenor(code, Period.ofDays(1))
            val match = requireNotNull(PATTERN.matchEntire(code)) { "tenor '$raw' is not ON or <n>D|W|M|Y" }
            val n = match.groupValues[1].toInt()
            val period = when (match.groupValues[2]) {
                "D" -> Period.ofDays(n).also { require(n <= MAX_DAYS) { "tenor '$raw': use W/M for more than a week" } }
                "W" -> Period.ofWeeks(n).also { require(n <= MAX_WEEKS) { "tenor '$raw' exceeds one year" } }
                "M" -> Period.ofMonths(n).also { require(n <= MAX_MONTHS) { "tenor '$raw' exceeds one year" } }
                else -> Period.ofYears(n).also { require(n <= MAX_YEARS) { "tenor '$raw' exceeds one year" } }
            }
            return Tenor(code, period)
        }
    }
}

/** The instrument a curve quote prices; stored beside the quote so a set re-reads unambiguously. */
enum class CurveInstrument { DEPOSIT, FRA, SWAP }

/** One market quote a curve is bootstrapped from. [rate] is a fraction (`0.035` = 3.5 %). */
sealed interface CurveQuote {
    val code: String
    val rate: BigDecimal
    val instrument: CurveInstrument

    companion object {
        /** Parses a stored or uploaded quote by instrument type. */
        fun of(instrument: CurveInstrument, code: String, rate: BigDecimal): CurveQuote = when (instrument) {
            CurveInstrument.DEPOSIT -> MoneyMarketQuote(Tenor.parse(code), rate)
            CurveInstrument.FRA -> FraQuote.parse(code, rate)
            CurveInstrument.SWAP -> SwapQuote.parse(code, rate)
        }
    }
}

/** A quoted simple (money-market) deposit rate from as-of to its tenor end. */
data class MoneyMarketQuote(val tenor: Tenor, val simpleRate: BigDecimal) : CurveQuote {
    override val code: String get() = tenor.code
    override val rate: BigDecimal get() = simpleRate
    override val instrument: CurveInstrument get() = CurveInstrument.DEPOSIT
}

/** A forward-rate agreement `<start>x<end>` in months from as-of (e.g. `3x6`), simple rate. */
data class FraQuote(val startMonths: Int, val endMonths: Int, override val rate: BigDecimal) : CurveQuote {
    init {
        require(startMonths in 1 until endMonths && endMonths <= MAX_END_MONTHS) {
            "FRA ${startMonths}x$endMonths: need 1 <= start < end <= $MAX_END_MONTHS months"
        }
    }

    override val code: String get() = "${startMonths}x$endMonths"
    override val instrument: CurveInstrument get() = CurveInstrument.FRA

    companion object {
        private const val MAX_END_MONTHS = 24
        private val PATTERN = Regex("^([1-9][0-9]?)X([1-9][0-9]?)$")

        fun parse(raw: String, rate: BigDecimal): FraQuote {
            val m = requireNotNull(PATTERN.matchEntire(raw.trim().uppercase())) { "FRA tenor '$raw' is not <m>x<n>" }
            return FraQuote(m.groupValues[1].toInt(), m.groupValues[2].toInt(), rate)
        }
    }
}

/** A par fixed-versus-float swap rate for `<n>Y`, fixed leg on the currency's [CurveConventions]. */
data class SwapQuote(val years: Int, override val rate: BigDecimal) : CurveQuote {
    init {
        require(years in MIN_YEARS..MAX_YEARS) { "swap tenor ${years}Y outside $MIN_YEARS..$MAX_YEARS years" }
    }

    override val code: String get() = "${years}Y"
    override val instrument: CurveInstrument get() = CurveInstrument.SWAP

    companion object {
        private const val MIN_YEARS = 1
        private const val MAX_YEARS = 50
        private val PATTERN = Regex("^([1-9][0-9]?)Y$")

        fun parse(raw: String, rate: BigDecimal): SwapQuote {
            val m = requireNotNull(PATTERN.matchEntire(raw.trim().uppercase())) { "swap tenor '$raw' is not <n>Y" }
            return SwapQuote(m.groupValues[1].toInt(), rate)
        }
    }
}

/**
 * Quote conventions of one currency's market (generic market practice, ADR-0344 D8): deposits and
 * FRAs simple ACT/360; the swap fixed leg annual (CZK ACT/360, EUR 30/360 bond basis); dates
 * MODIFIED_FOLLOWING on the currency's kernel calendar. Spot lag is zero — every quote starts at
 * as-of (rolled FOLLOWING onto a business day) — a stated simplification of this model version.
 */
data class CurveConventions(
    val calendar: FinanceCalendar,
    val moneyMarketDayCount: DayCount,
    val fixedFrequency: Frequency,
    val fixedDayCount: DayCount,
    val roll: DateRoll = DateRoll.MODIFIED_FOLLOWING,
) {
    companion object {
        fun forCurrency(currency: String): CurveConventions = when (currency) {
            "EUR" -> CurveConventions(
                Calendars.forCurrencies("EUR"),
                DayCount.ACT_360,
                Frequency.ANNUAL,
                DayCount.THIRTY_360_BOND_BASIS,
            )
            else -> CurveConventions(
                Calendars.forCurrencies(currency),
                DayCount.ACT_360,
                Frequency.ANNUAL,
                DayCount.ACT_360,
            )
        }
    }
}

/** Why a bootstrap was refused — typed, so an API maps it to a 400 naming the quote. */
class CurveBootstrapException(val index: CurveIndex, val reason: Reason, detail: String) :
    IllegalArgumentException("curve ${index.name}: $reason — $detail") {
    enum class Reason { NO_QUOTES, NOT_A_FRACTION, DUPLICATE_PILLAR, NO_CONVERGENCE, NOT_ARBITRAGE_FREE }
}

/**
 * Bootstraps a zero curve from deposits, FRAs and par swaps (model [VERSION], ADR-0344 D8).
 *
 * Quotes are sorted by the date their last flow pays; each adds ONE pillar there, whose zero rate
 * is solved (secant, to 1e-16 of PV per unit notional) so the quote's own instrument — built with
 * libs-finance's [LegTemplates] and priced with its [LegPricer], never a local formula — has zero
 * PV on the curve so far plus that pillar. Single curve per index: the floating leg of a swap
 * projects from the same curve it is discounted on, so a par swap telescopes to `DF(s) − DF(e)`.
 *
 * **Interpolation** is the [Curve]'s own: linear in continuously compounded zero rate over
 * ACT/365F time, flat beyond both ends. It is part of this version; a different scheme is a new
 * version, never an edit.
 *
 * **Sanity (no-arbitrage):** every pillar's discount factor must be positive and strictly below its
 * predecessor (and below 1); otherwise [CurveBootstrapException.Reason.NOT_ARBITRAGE_FREE].
 */
object CurveBootstrap {
    /** Recorded in `ModelVersions.curveModelVersion` of every run that prices on a bootstrapped set. */
    const val VERSION = "curve-bootstrap-v2:deposit-fra-swap;linear-zero-act365f;mm-act360"

    private val MAX_ABS_RATE = BigDecimal.ONE

    /** Notional the quotes are priced on: scale-12 rounding of its flows is 1e-18 relative. */
    val UNIT: BigDecimal = BigDecimal("1000000")
    private val TOLERANCE = BigDecimal("1e-10")
    private val FIRST_STEP = BigDecimal("0.0001")
    private const val MAX_ITERATIONS = 60

    fun bootstrap(
        index: CurveIndex,
        asOf: LocalDate,
        quotes: List<CurveQuote>,
        conventions: CurveConventions = CurveConventions.forCurrency(index.currency),
    ): Curve {
        validate(index, quotes)
        val instruments = quotes.map { Instrument(it, legs(index, asOf, it, conventions)) }
            .sortedBy { it.pillarDate }
        instruments.zipWithNext().forEach { (a, b) ->
            if (a.pillarDate == b.pillarDate) {
                throw CurveBootstrapException(
                    index,
                    CurveBootstrapException.Reason.DUPLICATE_PILLAR,
                    "${a.quote.code} and ${b.quote.code} both end on ${a.pillarDate}",
                )
            }
        }
        val pillars = mutableListOf<CurvePillar>()
        for (instrument in instruments) {
            val z = solve(index, asOf, pillars, instrument)
            pillars += CurvePillar(instrument.pillarDate, z)
        }
        return Curve(index, asOf, pillars).also { checkArbitrageFree(it) }
    }

    private fun validate(index: CurveIndex, quotes: List<CurveQuote>) {
        if (quotes.isEmpty()) {
            throw CurveBootstrapException(
                index,
                CurveBootstrapException.Reason.NO_QUOTES,
                "none given",
            )
        }
        quotes.forEach {
            if (it.rate.abs() >= MAX_ABS_RATE) {
                throw CurveBootstrapException(
                    index,
                    CurveBootstrapException.Reason.NOT_A_FRACTION,
                    "${it.instrument} ${it.code} rate ${it.rate}",
                )
            }
        }
    }

    /** The libs-finance legs of [quote]'s instrument on [UNIT] notional (scale-12 rounding). */
    fun legs(index: CurveIndex, asOf: LocalDate, quote: CurveQuote, c: CurveConventions): List<LegSpec> {
        val ccy = CurrencyCode.of(index.currency)
        // Spot is as-of itself on a business day, else the next one — never rolled BACK before as-of,
        // where its initial exchange would be a settled flow the par condition cannot see.
        val spot = c.calendar.roll(asOf, DateRoll.FOLLOWING)
        fun terms(start: LocalDate, end: LocalDate) =
            DealTerms(ccy, UNIT, start, end, c.calendar, c.roll, RoundingPolicy.TREASURY_INTEREST_WORK)
        return when (quote) {
            is MoneyMarketQuote -> listOf(
                LegTemplates.moneyMarket(
                    PayReceive.RECEIVE,
                    terms(spot, depositEnd(asOf, quote.tenor.endDate(asOf), c)),
                    quote.rate,
                    c.moneyMarketDayCount,
                ),
            )
            // A FRA at par is a forward-starting deposit: −1 at its start, (1 + Kτ) at its end.
            is FraQuote -> listOf(
                LegTemplates.moneyMarket(
                    PayReceive.RECEIVE,
                    terms(asOf.plusMonths(quote.startMonths.toLong()), asOf.plusMonths(quote.endMonths.toLong())),
                    quote.rate,
                    c.moneyMarketDayCount,
                ),
            )
            is SwapQuote -> LegTemplates.interestRateSwap(
                PayReceive.RECEIVE,
                terms(spot, asOf.plusYears(quote.years.toLong())),
                FixedLegTerms(quote.rate, c.fixedFrequency, c.fixedDayCount),
                FloatingLegTerms(
                    index.name,
                    floatFrequency(index, c),
                    c.moneyMarketDayCount,
                    fixingLagBusinessDays = 0,
                ),
            )
        }
    }

    /** PV on [UNIT] notional of [legs] on [curve], every flow from as-of on (an as-of flow has DF 1). */
    fun parResidual(legs: List<LegSpec>, curve: Curve): BigDecimal = legs.fold(BigDecimal.ZERO) { pv, leg ->
        val schedule = ScheduleGenerator.generate(leg)
        val flows = CashFlowProjector.project(schedule, forward = curve.projection()).requireComplete()
        pv.add(LegPricer.presentValue(flows, curve, curve.asOf.minusDays(1)), BigMath.MC)
    }

    /**
     * On a non-business as-of (a weekend, a holiday) the deposit's start rolls forward and a short
     * tenor's end can roll onto the same day; the deposit then runs to the next business day after
     * its rolled start — the overnight convention — rather than collapsing.
     */
    private fun depositEnd(asOf: LocalDate, end: LocalDate, c: CurveConventions): LocalDate {
        val start = c.calendar.roll(asOf, DateRoll.FOLLOWING)
        return if (c.calendar.roll(end, c.roll).isAfter(start)) end else c.calendar.nextBusinessDay(start)
    }

    /** OIS curves have no tenor; single-curve projection telescopes, so the fixed frequency serves. */
    private fun floatFrequency(index: CurveIndex, c: CurveConventions): Frequency = when (index) {
        CurveIndex.PRIBOR_1M -> Frequency.MONTHLY
        CurveIndex.PRIBOR_3M, CurveIndex.EURIBOR_3M -> Frequency.QUARTERLY
        CurveIndex.PRIBOR_6M -> Frequency.SEMI_ANNUAL
        CurveIndex.CZEONIA, CurveIndex.ESTR -> c.fixedFrequency
    }

    private class Instrument(val quote: CurveQuote, val legs: List<LegSpec>) {
        val pillarDate: LocalDate = legs.maxOf { leg ->
            ScheduleGenerator.generate(leg).periods.maxOf { it.paymentDate }
        }
    }

    private fun solve(
        index: CurveIndex,
        asOf: LocalDate,
        known: List<CurvePillar>,
        instrument: Instrument,
    ): BigDecimal {
        fun residual(z: BigDecimal) =
            parResidual(instrument.legs, Curve(index, asOf, known + CurvePillar(instrument.pillarDate, z)))
        var x0 = known.lastOrNull()?.zeroRate ?: instrument.quote.rate
        var x1 = x0.add(FIRST_STEP)
        var f0 = residual(x0)
        repeat(MAX_ITERATIONS) {
            val f1 = residual(x1)
            if (f1.abs() <= TOLERANCE) return x1
            val slope = f1.subtract(f0, BigMath.MC)
            if (slope.signum() == 0) return@repeat
            val next = x1.subtract(
                f1.multiply(x1.subtract(x0, BigMath.MC), BigMath.MC).divide(slope, BigMath.MC),
                BigMath.MC,
            )
            x0 = x1
            f0 = f1
            x1 = next
        }
        if (residual(x1).abs() <= TOLERANCE) return x1
        throw CurveBootstrapException(
            index,
            CurveBootstrapException.Reason.NO_CONVERGENCE,
            "${instrument.quote.instrument} ${instrument.quote.code} on $asOf did not reprice to par " +
                "(residual ${residual(x1)} at zero rate $x1)",
        )
    }

    private fun checkArbitrageFree(curve: Curve) {
        var previous = BigDecimal.ONE
        curve.pillars.forEach { p ->
            val df = curve.discountFactor(p.date)
            if (df.signum() <= 0 || df >= previous) {
                throw CurveBootstrapException(
                    curve.index,
                    CurveBootstrapException.Reason.NOT_ARBITRAGE_FREE,
                    "discount factor $df on ${p.date} is not positive and strictly below $previous",
                )
            }
            previous = df
        }
    }
}
