// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.product

import com.openbank.libs.domain.calendar.BusinessDayConvention
import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.calendar.YearFraction
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.FixingConvention
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.PayReceive
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.leg.ScheduleSpec
import com.openbank.libs.finance.pricing.FraPricer
import com.openbank.libs.finance.schedule.DateRange
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.StubType
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/**
 * The common terms of a deal: currency, notional, period and calendar. Product typing (ADR-0344
 * D2) belongs to the treasury service; these are plain leg templates.
 */
data class DealTerms(
    val currency: CurrencyCode,
    val notional: BigDecimal,
    val start: LocalDate,
    val end: LocalDate,
    val calendar: FinanceCalendar,
    val roll: BusinessDayConvention = BusinessDayConvention.MODIFIED_FOLLOWING,
    val rounding: RoundingPolicy = RoundingPolicy.TREASURY_AMOUNT,
)

/** The fixed side of a swap. */
data class FixedLegTerms(val rate: BigDecimal, val frequency: Frequency, val dayCount: DayCount)

/** The floating side of a swap; fixes [fixingLagBusinessDays] before each accrual start. */
data class FloatingLegTerms(
    val index: String,
    val frequency: Frequency,
    val dayCount: DayCount,
    val spread: BigDecimal = BigDecimal.ZERO,
    val fixingLagBusinessDays: Int = 2,
    val fixingCalendar: FinanceCalendar? = null,
)

/** A security delivered as collateral, by reference only; the securities master owns its terms. */
data class CollateralReference(val isin: String, val nominal: BigDecimal)

/** A repo: the cash leg plus a reference to its collateral. */
data class RepoContract(val cashLeg: LegSpec, val collateral: CollateralReference)

/**
 * A forward rate agreement. Its single flow is the settlement amount, known only on the fixing
 * date and paid at the period start — see [settlementAmount].
 */
data class FraContract(
    val side: PayReceive,
    val notional: BigDecimal,
    val contractRate: BigDecimal,
    val period: DateRange,
    val fixingDate: LocalDate,
    val index: String,
    val dayCount: DayCount,
) {
    val yearFraction: YearFraction get() = dayCount.yearFraction(period.start, period.end)

    /** Settlement amount for an observed [fixing], signed for [side] (unrounded). */
    fun settlementAmount(fixing: BigDecimal): BigDecimal =
        FraPricer.settlementAmount(notional, contractRate, fixing, yearFraction, side)
}

/** Factory functions for the common treasury leg shapes. */
object LegTemplates {
    private fun bullet(t: DealTerms, frequency: Frequency = Frequency.Zero) = ScheduleSpec(t.start, t.end, frequency)

    private fun rules(t: DealTerms) = DateRules(t.calendar, t.roll)

    /**
     * Money-market deposit (bank places: [PayReceive.RECEIVE]) or loan taken (bank borrows:
     * [PayReceive.PAY]): principal out at start, principal plus one interest flow at maturity.
     */
    fun moneyMarket(
        side: PayReceive,
        terms: DealTerms,
        rate: BigDecimal,
        dayCount: DayCount = DayCount.ACT_360,
    ): LegSpec = LegSpec(
        payReceive = side,
        currency = terms.currency,
        notional = NotionalSpec(terms.notional, initialExchange = true, finalExchange = true),
        schedule = bullet(terms),
        dates = rules(terms),
        rate = RateSpec.Fixed(rate),
        dayCount = dayCount,
        rounding = terms.rounding,
    )

    /**
     * Treasury bill bought at a discount: the bank pays the price `face / (1 + y·τ)` at start and
     * receives [DealTerms.notional] (the face) at maturity. [yield] is the simple money-market yield.
     */
    fun treasuryBill(terms: DealTerms, yield: BigDecimal, dayCount: DayCount = DayCount.ACT_360): LegSpec {
        val tau = dayCount.yearFraction(terms.start, terms.end).toBigDecimal(MathContext.DECIMAL128)
        val price = terms.rounding.round(
            terms.notional.divide(BigDecimal.ONE + yield.multiply(tau), MathContext.DECIMAL128),
            terms.currency,
        )
        return LegSpec(
            payReceive = PayReceive.RECEIVE,
            currency = terms.currency,
            notional = NotionalSpec(
                price,
                initialExchange = true,
                finalExchange = true,
                finalExchangeAmount = terms.notional,
            ),
            schedule = bullet(terms),
            dates = rules(terms),
            rate = RateSpec.None,
            dayCount = dayCount,
            rounding = terms.rounding,
        )
    }

    /**
     * Fixed-coupon bond held: regular coupons generated backward from maturity (short first
     * coupon), unadjusted accrual with payments rolled (the bond-market convention), EOM rule on,
     * redemption at face on the last payment date. The purchase itself is a settlement event, not
     * a leg flow.
     */
    fun fixedCouponBond(terms: DealTerms, coupon: FixedLegTerms): LegSpec = LegSpec(
        payReceive = PayReceive.RECEIVE,
        currency = terms.currency,
        notional = NotionalSpec(terms.notional, finalExchange = true),
        schedule = ScheduleSpec(
            terms.start,
            terms.end,
            coupon.frequency,
            stub = StubType.SHORT_INITIAL,
            endOfMonth = true,
        ),
        dates = DateRules(
            terms.calendar,
            BusinessDayConvention.FOLLOWING,
            adjustAccrualPeriod = false,
            adjustPaymentDate = true,
        ),
        rate = RateSpec.Fixed(coupon.rate),
        dayCount = coupon.dayCount,
        rounding = terms.rounding,
    )

    /** FRA over [DealTerms.start, DealTerms.end), fixing [fixingLagBusinessDays] before the start. */
    fun fra(
        side: PayReceive,
        terms: DealTerms,
        contractRate: BigDecimal,
        index: String,
        dayCount: DayCount = DayCount.ACT_360,
        fixingLagBusinessDays: Int = 2,
    ): FraContract {
        val start = terms.calendar.roll(terms.start, terms.roll)
        val end = terms.calendar.roll(terms.end, terms.roll)
        return FraContract(
            side = side,
            notional = terms.notional,
            contractRate = contractRate,
            period = DateRange(start, end),
            fixingDate = terms.calendar.addBusinessDays(start, -fixingLagBusinessDays),
            index = index,
            dayCount = dayCount,
        )
    }

    /**
     * Fixed-versus-floating interest-rate swap: `[fixed leg, floating leg]`, no notional exchange.
     * [fixedSide] is the bank's side of the FIXED leg (a payer swap is [PayReceive.PAY]).
     */
    fun interestRateSwap(
        fixedSide: PayReceive,
        terms: DealTerms,
        fixed: FixedLegTerms,
        floating: FloatingLegTerms,
    ): List<LegSpec> {
        val notional = NotionalSpec(terms.notional, finalExchange = false)
        val fixedLeg = LegSpec(
            payReceive = fixedSide,
            currency = terms.currency,
            notional = notional,
            schedule = ScheduleSpec(terms.start, terms.end, fixed.frequency),
            dates = rules(terms),
            rate = RateSpec.Fixed(fixed.rate),
            dayCount = fixed.dayCount,
            rounding = terms.rounding,
        )
        val floatingLeg = fixedLeg.copy(
            payReceive = if (fixedSide == PayReceive.PAY) PayReceive.RECEIVE else PayReceive.PAY,
            schedule = ScheduleSpec(terms.start, terms.end, floating.frequency),
            rate = RateSpec.Floating(
                index = floating.index,
                spread = floating.spread,
                fixing = FixingConvention(floating.fixingLagBusinessDays, floating.fixingCalendar ?: terms.calendar),
                resetFrequency = floating.frequency,
            ),
            dayCount = floating.dayCount,
        )
        return listOf(fixedLeg, floatingLeg)
    }

    /**
     * FX outright forward: the bank receives [buy] and pays [sell] on [valueDate]. Each side is a
     * no-interest leg from [tradeDate] to [valueDate] with a single final exchange.
     */
    fun fxForward(
        tradeDate: LocalDate,
        valueDate: LocalDate,
        buy: FxAmount,
        sell: FxAmount,
        calendar: FinanceCalendar,
    ): List<LegSpec> = listOf(
        fxLeg(PayReceive.RECEIVE, buy, tradeDate, valueDate, calendar),
        fxLeg(PayReceive.PAY, sell, tradeDate, valueDate, calendar),
    )

    /**
     * FX swap on [baseNotional] of base: near exchange at [nearRate] on the near date, reversed at
     * [farRate] on the far date. `bankBuysBaseNear = true` is a buy/sell swap of the base currency.
     * Two legs, each with an initial and a final exchange; the quote leg's far amount differs from
     * its near amount by the forward points.
     */
    fun fxSwap(
        near: LocalDate,
        far: LocalDate,
        base: FxAmount,
        quote: FxSwapQuote,
        calendar: FinanceCalendar,
    ): List<LegSpec> {
        val baseSide = if (quote.bankBuysBaseNear) PayReceive.PAY else PayReceive.RECEIVE
        val quoteSide = if (quote.bankBuysBaseNear) PayReceive.RECEIVE else PayReceive.PAY
        val rounding = RoundingPolicy.TREASURY_AMOUNT
        val nearQuote = rounding.round(base.amount.multiply(quote.nearRate), quote.currency)
        val farQuote = rounding.round(base.amount.multiply(quote.farRate), quote.currency)
        return listOf(
            swapLeg(baseSide, base.currency, base.amount, null, DateRange(near, far), calendar),
            swapLeg(quoteSide, quote.currency, nearQuote, farQuote, DateRange(near, far), calendar),
        )
    }

    /**
     * Repo: the bank sells [collateral] and borrows cash ([PayReceive.PAY] on the cash leg: cash in
     * at start, repurchase price out at end); a reverse repo is [PayReceive.RECEIVE].
     */
    fun repo(
        side: PayReceive,
        terms: DealTerms,
        repoRate: BigDecimal,
        collateral: CollateralReference,
        dayCount: DayCount = DayCount.ACT_360,
    ): RepoContract = RepoContract(moneyMarket(side, terms, repoRate, dayCount), collateral)
}

private fun fxLeg(side: PayReceive, amount: FxAmount, start: LocalDate, end: LocalDate, calendar: FinanceCalendar) =
    LegSpec(
        payReceive = side,
        currency = amount.currency,
        notional = NotionalSpec(amount.amount, finalExchange = true),
        schedule = ScheduleSpec(start, end, Frequency.Zero),
        dates = DateRules(calendar, BusinessDayConvention.FOLLOWING),
        rate = RateSpec.None,
        dayCount = DayCount.ACT_360,
    )

private fun swapLeg(
    side: PayReceive,
    currency: CurrencyCode,
    nearAmount: BigDecimal,
    farAmount: BigDecimal?,
    period: DateRange,
    calendar: FinanceCalendar,
) = LegSpec(
    payReceive = side,
    currency = currency,
    notional = NotionalSpec(
        nearAmount,
        initialExchange = true,
        finalExchange = true,
        finalExchangeAmount = farAmount,
    ),
    schedule = ScheduleSpec(period.start, period.end, Frequency.Zero),
    dates = DateRules(calendar, BusinessDayConvention.FOLLOWING),
    rate = RateSpec.None,
    dayCount = DayCount.ACT_360,
)

/** An amount in a currency, for FX templates. */
data class FxAmount(val currency: CurrencyCode, val amount: BigDecimal)

/** The quote side of an FX swap: quote currency per one base unit at the near and far dates. */
data class FxSwapQuote(
    val currency: CurrencyCode,
    val nearRate: BigDecimal,
    val farRate: BigDecimal,
    val bankBuysBaseNear: Boolean,
)
