// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.schedule.Frequency
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import com.openbank.libs.domain.calendar.DayCount as KernelDayCount

/**
 * ADR-0344 D2: only ACTUAL books, posts and pays; PLANNED feeds the forecast; HYPOTHETICAL is priced
 * only. Owned by `openbank-libs-finance` (risk-engine values the same kinds).
 */
typealias DealKind = com.openbank.libs.finance.deal.DealKind

enum class PayReceive { PAY, RECEIVE }

/** NONE = an exchange-only leg (an FX leg): notional moves, nothing accrues. */
enum class RateType { FIXED, FLOATING, NONE }

enum class AmortisationType { BULLET, LINEAR, ANNUITY, CUSTOM }

/**
 * Where a leg's irregular period goes: `openbank-libs-finance`'s stub type. A leg that names none
 * (`null`, spelled `NONE` on the wire) lets the deal's [RollDirection] place it.
 */
typealias StubType = com.openbank.libs.finance.schedule.StubType

/** The direction a deal's schedule rolls in; owned by `openbank-libs-finance`. */
typealias RollDirection = com.openbank.libs.finance.schedule.RollDirection

enum class CapFloorBasis { INDEX, INDEX_PLUS_SPREAD }

/** The wire names of the day counts a leg may use, each backed by the kernel's [KernelDayCount]. */
enum class DayCountConvention(val wire: String, val kernel: KernelDayCount) {
    ACT_360("ACT/360", KernelDayCount.ACT_360),
    ACT_365F("ACT/365F", KernelDayCount.ACT_365_FIXED),
    ACT_ACT_ISDA("ACT/ACT_ISDA", KernelDayCount.ACT_ACT_ISDA),
    THIRTY_360("30/360", KernelDayCount.THIRTY_360_BOND_BASIS),
    THIRTY_E_360("30E/360", KernelDayCount.THIRTY_E_360),
    ;

    companion object {
        fun fromWire(value: String): DayCountConvention = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("dayCount must be one of ${entries.map { it.wire }}")
    }
}

data class LegRate(
    val type: RateType,
    val rate: BigDecimal? = null,
    val index: String? = null,
    val spread: BigDecimal? = null,
    val fixingLagBusinessDays: Int? = null,
    val resetFrequency: Frequency? = null,
    val cap: BigDecimal? = null,
    val floor: BigDecimal? = null,
    val capFloorAppliesTo: CapFloorBasis? = null,
    val rateUntilFirstFixing: BigDecimal? = null,
) {
    fun validate() {
        when (type) {
            RateType.FIXED -> {
                requireNotNull(rate) { "a FIXED leg needs a rate" }
                require(index == null && spread == null && cap == null && floor == null) {
                    "a FIXED leg carries no index, spread, cap or floor"
                }
            }
            RateType.NONE -> require(rate == null && index == null) { "an exchange-only leg carries no rate" }
            RateType.FLOATING -> {
                require(!index.isNullOrBlank()) { "a FLOATING leg needs an index" }
                require(rate == null) { "a FLOATING leg's rate comes from its fixings" }
                fixingLagBusinessDays?.let { require(it in 0..MAX_LAG) { "fixingLagBusinessDays must be 0..$MAX_LAG" } }
                if (cap != null && floor != null) require(cap >= floor) { "cap must not be below floor" }
                if (cap != null ||
                    floor != null
                ) {
                    requireNotNull(capFloorAppliesTo) { "a cap or floor says what it applies to" }
                }
            }
        }
    }

    companion object {
        const val MAX_LAG = 10

        fun fixed(rate: BigDecimal) = LegRate(RateType.FIXED, rate = rate)

        val NONE = LegRate(RateType.NONE)
    }
}

data class AmortisationStep(val date: LocalDate, val amount: BigDecimal)

data class Amortisation(val type: AmortisationType, val schedule: List<AmortisationStep> = emptyList()) {
    companion object {
        val BULLET = Amortisation(AmortisationType.BULLET)
    }
}

/**
 * One leg of an instrument (ADR-0344 D2), as the shared schedule engine's leg spec describes it.
 * Persisted with [SCHEMA_VERSION] so a later shape can be read beside today's.
 */
data class LegSpec(
    val payReceive: PayReceive,
    val currency: String,
    val notional: BigDecimal,
    val accrualStart: LocalDate,
    val accrualEnd: LocalDate,
    val dayCount: DayCountConvention,
    val rate: LegRate,
    val amortisation: Amortisation = Amortisation.BULLET,
    /** `openbank-libs-finance`'s schedule frequency; a leg uses only the [Frequency.NAMED] ones (wire: their codes). */
    val accrualFrequency: Frequency = Frequency.Zero,
    val paymentFrequency: Frequency = Frequency.Zero,
    /** null = no stub named: the deal's roll direction places it (`NONE` on the wire). */
    val stub: StubType? = null,
    val adjustPaymentDate: Boolean = true,
    val adjustAccrualPeriod: Boolean = false,
    val paymentLagBusinessDays: Int = 0,
    val initialNotionalExchange: Boolean = false,
    val finalNotionalExchange: Boolean = false,
    /** First amortisation date; null = from accrual start. */
    val amortisationStart: LocalDate? = null,
    /** `calendar` reference keys for payment dates; empty = the deal's calendars. */
    val paymentCalendars: List<String> = emptyList(),
    /** Month-end anchors stay at month end when rolled (ISDA end-of-month rule). */
    val endOfMonth: Boolean = false,
) {
    fun validate() {
        require(currency.length == CCY_LENGTH && currency.all { it in 'A'..'Z' }) { "leg currency must be ISO 4217" }
        require(notional.signum() > 0) { "leg notional must be positive" }
        require(notional.scale() <= 2) { "leg notional has at most 2 decimal places" }
        require(!accrualEnd.isBefore(accrualStart)) { "accrualEnd must not precede accrualStart" }
        // Size bounds before any schedule is generated (a request must not buy unbounded work).
        require(!accrualEnd.isAfter(accrualStart.plusYears(MAX_TENOR_YEARS))) {
            "a leg runs at most $MAX_TENOR_YEARS years"
        }
        require(amortisation.schedule.size <= MAX_AMORTISATION_ROWS) {
            "a custom amortisation has at most $MAX_AMORTISATION_ROWS rows"
        }
        require(paymentCalendars.size <= MAX_CALENDARS) { "a leg names at most $MAX_CALENDARS payment calendars" }
        require(expectedPeriods() <= MAX_PERIODS_PER_LEG) {
            "a leg generates at most $MAX_PERIODS_PER_LEG periods (this one ${expectedPeriods()}: tenor / frequency)"
        }
        listOfNotNull(accrualFrequency, paymentFrequency, rate.resetFrequency).forEach {
            require(Frequency.codeOf(it) != null) { "a leg frequency is one of ${Frequency.NAMED.keys}" }
        }
        require(paymentLagBusinessDays in 0..LegRate.MAX_LAG) { "paymentLagBusinessDays must be 0..${LegRate.MAX_LAG}" }
        rate.validate()
        amortisationStart?.let {
            require(!it.isBefore(accrualStart) && !it.isAfter(accrualEnd)) {
                "amortisationStart lies within the accrual period"
            }
        }
        if (amortisation.type == AmortisationType.CUSTOM) {
            require(amortisation.schedule.isNotEmpty()) { "a CUSTOM amortisation lists its repayments" }
            require(amortisation.schedule.sumOf { it.amount }.compareTo(notional) == 0) {
                "a CUSTOM amortisation repays exactly the notional"
            }
        }
    }

    /**
     * An upper bound on the periods this leg generates (tenor / the finer of its two frequencies),
     * computed BEFORE the engine runs so a request cannot buy unbounded work.
     */
    fun expectedPeriods(): Long {
        val days = java.time.temporal.ChronoUnit.DAYS.between(accrualStart, accrualEnd)
        fun periods(f: Frequency): Long = when (f) {
            Frequency.Zero -> 1
            is Frequency.Days -> days / f.days
            is Frequency.Weeks -> days / (DAYS_PER_WEEK.toLong() * f.weeks) + 1
            is Frequency.Months -> java.time.temporal.ChronoUnit.MONTHS.between(accrualStart, accrualEnd) / f.months + 1
        }
        return maxOf(
            periods(accrualFrequency),
            periods(paymentFrequency),
            rate.resetFrequency?.let(::periods) ?: 0L,
            1L,
        )
    }

    /** True when the leg is one accrual period paying once — what the bullet generator handles. */
    val singlePeriod: Boolean
        get() = accrualFrequency == Frequency.Zero &&
            paymentFrequency == Frequency.Zero &&
            amortisation.type == AmortisationType.BULLET

    /**
     * SHA-256 of the leg's canonical text. An override survives regeneration only while the hash of
     * the leg that produced its flow is unchanged (ADR-0344 D4).
     */
    fun specHash(): String = sha256(if (endOfMonth) toString() else toString().removeSuffix(NO_EOM_SUFFIX) + ")")

    companion object {
        const val SCHEMA_VERSION = 1

        /** A leg without the EOM rule hashes exactly as before the field existed (overrides survive). */
        private const val NO_EOM_SUFFIX = ", endOfMonth=false)"
        const val MAX_TENOR_YEARS = 60L
        const val MAX_AMORTISATION_ROWS = 2000
        const val MAX_CALENDARS = 10
        const val MAX_PERIODS_PER_LEG = 2000L
        private const val DAYS_PER_WEEK = 7
        private const val CCY_LENGTH = 3

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The legs of the five products that predate ADR-0344, derived from their flat fields so their
 * behaviour is unchanged: one fixed ACT/360 bullet period over `[valueDate, maturityDate)` with
 * both notional exchanges, and for FX spot one zero-rate exchange per currency on the value date.
 * Dates are taken as captured (`adjustPaymentDate = false`): the legacy products never rolled a
 * date after capture, and deriving their legs must not start to.
 */
object LegacyLegs {
    fun derive(deal: Deal): List<LegSpec> = when (deal.product) {
        ProductType.FX_SPOT -> fxSpot(deal)
        ProductType.MM_PLACEMENT, ProductType.CNB_DEPOSIT_FACILITY -> listOf(moneyMarket(deal, PayReceive.RECEIVE))
        ProductType.MM_BORROWING, ProductType.CNB_LOMBARD -> listOf(moneyMarket(deal, PayReceive.PAY))
        else -> emptyList()
    }

    private fun moneyMarket(deal: Deal, side: PayReceive) = LegSpec(
        payReceive = side,
        currency = deal.currency,
        notional = deal.principal,
        accrualStart = deal.valueDate,
        accrualEnd = deal.maturityDate,
        dayCount = DayCountConvention.ACT_360,
        rate = LegRate.fixed(deal.rate),
        adjustPaymentDate = false,
        initialNotionalExchange = true,
        finalNotionalExchange = true,
    )

    /** The bank RECEIVES what it buys and PAYS what it sells, both on the value date. */
    private fun fxSpot(deal: Deal): List<LegSpec> {
        val fx = checkNotNull(deal.fx) { "an FX spot deal carries FX terms" }
        fun exchange(side: PayReceive, currency: String, amount: BigDecimal) = LegSpec(
            payReceive = side,
            currency = currency,
            notional = amount,
            accrualStart = deal.valueDate,
            accrualEnd = deal.valueDate,
            dayCount = DayCountConvention.ACT_360,
            rate = LegRate.NONE,
            adjustPaymentDate = false,
            finalNotionalExchange = true,
        )
        val foreign = if (fx.side == FxSide.BUY) PayReceive.RECEIVE else PayReceive.PAY
        val czk = if (fx.side == FxSide.BUY) PayReceive.PAY else PayReceive.RECEIVE
        return listOf(exchange(foreign, deal.currency, deal.principal), exchange(czk, Deal.CZK, fx.counterAmount))
    }
}

/** Simple interest over one accrual period, through the kernel day count. */
object SimpleInterest {
    private val PERCENT = BigDecimal("100")

    /** notional × rate/100 × yearFraction, the fraction kept exact until the one division; half-up to 2 dp. */
    fun of(
        notional: BigDecimal,
        ratePercent: BigDecimal,
        dayCount: DayCountConvention,
        from: LocalDate,
        to: LocalDate,
    ): BigDecimal {
        val fraction = dayCount.kernel.yearFraction(from, to)
        val quotient = RoundingPolicy.TREASURY_INTEREST_WORK.divide(
            notional.multiply(ratePercent).multiply(BigDecimal.valueOf(fraction.numerator)),
            PERCENT.multiply(BigDecimal.valueOf(fraction.denominator)),
        )
        return RoundingPolicy.TREASURY_AMOUNT.round(quotient)
    }
}
