// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.finance

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.libs.domain.calendar.BusinessCalendar
import com.openbank.libs.domain.calendar.BusinessDayConvention
import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.calendar.Calendars
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.CapFloorAppliesTo
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.FixingConvention
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.RateBounds
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.leg.ScheduleSpec
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.RollDirection
import com.openbank.libs.finance.schedule.StubType
import io.quarkus.runtime.annotations.RegisterForReflection
import java.math.BigDecimal
import java.time.LocalDate

/** `{date, amount}` of a CUSTOM amortisation (contract section 2). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
data class AmortisationRowDto(val date: String? = null, val amount: BigDecimal? = null)

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
data class AmortisationDto(val type: String? = null, val schedule: List<AmortisationRowDto>? = null)

/** `{type: FIXED, rate}` or `{type: FLOATING, index, spread, ...}` (contract section 2). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
data class RateDto(
    val type: String? = null,
    val rate: BigDecimal? = null,
    val index: String? = null,
    val spread: BigDecimal? = null,
    val fixingLagBusinessDays: Int? = null,
    val resetFrequency: String? = null,
    val cap: BigDecimal? = null,
    val floor: BigDecimal? = null,
    val capFloorAppliesTo: String? = null,
    val rateUntilFirstFixing: BigDecimal? = null,
    val fixingInArrears: Boolean? = null,
)

/**
 * The wire shape of one leg — treasury's `LegSpec` JSON (ADR-0344 contract section 2, plus the
 * section 8.1 per-leg `paymentCalendars`). Unknown fields are ignored: this engine reads what it
 * prices and nothing else.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
data class LegDto(
    val payReceive: String? = null,
    val currency: String? = null,
    val notional: BigDecimal? = null,
    val amortisation: AmortisationDto? = null,
    val accrualStart: String? = null,
    val accrualEnd: String? = null,
    val accrualFrequency: String? = null,
    val paymentFrequency: String? = null,
    val stub: String? = null,
    val endOfMonth: Boolean? = null,
    val adjustPaymentDate: Boolean? = null,
    val adjustAccrualPeriod: Boolean? = null,
    val paymentLagBusinessDays: Int? = null,
    val dayCount: String? = null,
    val rate: RateDto? = null,
    val initialNotionalExchange: Boolean? = null,
    val finalNotionalExchange: Boolean? = null,
    val finalExchangeAmount: BigDecimal? = null,
    val paymentCalendars: List<String>? = null,
    val businessDayConvention: String? = null,
)

/**
 * How strictly a leg list is bounded. The bounds protect the PRICING edge from unbounded work; they
 * must never apply to a deal treasury has already BOOKED — refusing a real position there would
 * silently drop it from valuation, VaR and limits. A booked deal is bounded only by libs-finance's
 * own per-leg period cap, and a deal that fails it is stored as unvaluable, never discarded.
 */
enum class LegBounds(val maxLegs: Int?, val maxSpanYears: Long?, val maxLagBusinessDays: Int?) {
    PRICING_REQUEST(LegSpecJson.MAX_LEGS, LegSpecJson.MAX_SPAN_YEARS, LegSpecJson.MAX_LAG_BUSINESS_DAYS),
    BOOKED(null, null, null),
    ;

    /** A lag of `0..maxLagBusinessDays`, or absent; a breach is an [IllegalArgumentException] (400). */
    fun requireLag(i: Int, field: String, value: Int?) {
        val max = maxLagBusinessDays ?: return
        require(value == null || value in 0..max) { "legs[$i].$field $value must be between 0 and $max" }
    }
}

/** Deal-level defaults a leg inherits (contract section 8.1 header: `calendars`, `businessDayConvention`). */
data class LegDefaults(
    val calendars: List<String>? = null,
    val businessDayConvention: String? = null,
    /** The deal's roll direction; places a leg that names no stub. Absent = treasury's default, FROM_END. */
    val rollDirection: RollDirection = RollDirection.FROM_END,
) {
    companion object {
        /** `null`, blank and `NONE` all mean "no stub"; any other text must name a [StubType]. */
        fun parseStub(raw: String?, i: Int = 0): StubType? {
            val text = raw?.trim()?.uppercase()
            return if (text.isNullOrEmpty() || text == StubType.NO_STUB) null else stubNamed(i, text)
        }

        private fun stubNamed(i: Int, text: String): StubType {
            val known = StubType.entries
            return known.firstOrNull { it.name == text }
                ?: throw IllegalArgumentException("legs[$i].stub '$text' is not one of ${known.joinToString()}")
        }

        /** The deal's roll direction as published; absent/blank = treasury's default, FROM_END. */
        fun parseRollDirection(raw: String?): RollDirection {
            val text = raw?.trim()?.uppercase()
            if (text.isNullOrEmpty()) return RollDirection.FROM_END
            val known = RollDirection.entries
            return known.firstOrNull { it.name == text }
                ?: throw IllegalArgumentException("rollDirection '$raw' is not one of ${known.joinToString()}")
        }
    }
}

/**
 * LegSpec JSON → libs-finance [LegSpec]. Every defect is an [IllegalArgumentException] naming the
 * leg and the field (a 400 at the REST edge, a malformed event in the consumer). Rates are decimal
 * fractions per annum, as in libs-finance.
 */
object LegSpecJson {

    fun toLegs(legs: List<LegDto>?, defaults: LegDefaults, bounds: LegBounds): List<LegSpec> {
        val list = requireNotNull(legs?.takeIf { it.isNotEmpty() }) { "field 'legs' must hold at least one leg" }
        bounds.maxLegs?.let { max ->
            require(list.size <= max) { "field 'legs' holds ${list.size} legs; at most $max are accepted" }
        }
        return list.mapIndexed { i, dto -> toLeg(i, dto, defaults, bounds) }
    }

    @Suppress("CyclomaticComplexMethod")
    fun toLeg(i: Int, d: LegDto, defaults: LegDefaults, bounds: LegBounds): LegSpec {
        fun <T> req(value: T?, field: String): T = requireNotNull(value) { "legs[$i].$field is required" }
        val currency = req(d.currency, "currency").trim().uppercase()
        val accrualFrequency = frequency(i, "accrualFrequency", req(d.accrualFrequency, "accrualFrequency"))
        val calendar = calendar(d.paymentCalendars ?: defaults.calendars ?: listOf(currency))
        val roll = roll(i, d.businessDayConvention ?: defaults.businessDayConvention)
        val start = date(i, "accrualStart", req(d.accrualStart, "accrualStart"))
        val end = date(i, "accrualEnd", req(d.accrualEnd, "accrualEnd"))
        bounds.maxSpanYears?.let { years ->
            require(!end.isAfter(start.plusYears(years))) {
                "legs[$i]: accrualStart $start to accrualEnd $end exceeds $years years"
            }
        }
        bounds.requireLag(i, "paymentLagBusinessDays", d.paymentLagBusinessDays)
        bounds.requireLag(i, "rate.fixingLagBusinessDays", d.rate?.fixingLagBusinessDays)
        return LegSpec(
            payReceive = enumOf(i, "payReceive", req(d.payReceive, "payReceive")),
            currency = CurrencyCode.of(currency),
            notional = NotionalSpec(
                amount = req(d.notional, "notional"),
                amortisation = amortisation(i, d.amortisation),
                initialExchange = d.initialNotionalExchange ?: false,
                finalExchange = d.finalNotionalExchange ?: true,
                finalExchangeAmount = d.finalExchangeAmount,
            ),
            schedule = ScheduleSpec(
                start = start,
                end = end,
                accrualFrequency = accrualFrequency,
                paymentFrequency = d.paymentFrequency?.let { frequency(i, "paymentFrequency", it) } ?: accrualFrequency,
                stub = StubType.resolve(LegDefaults.parseStub(d.stub, i), defaults.rollDirection),
                endOfMonth = d.endOfMonth ?: false,
            ),
            dates = DateRules(
                calendar = calendar,
                roll = roll,
                adjustAccrualPeriod = d.adjustAccrualPeriod ?: true,
                adjustPaymentDate = d.adjustPaymentDate ?: true,
                paymentLagBusinessDays = d.paymentLagBusinessDays ?: 0,
            ),
            rate = rate(i, d.rate, accrualFrequency, calendar),
            dayCount = dayCount(i, d.dayCount ?: "ACT_360"),
        )
    }

    private fun rate(i: Int, r: RateDto?, accrual: Frequency, calendar: FinanceCalendar): RateSpec {
        if (r == null || r.type == null || r.type.equals("NONE", true)) return RateSpec.None
        return when (r.type.trim().uppercase()) {
            "FIXED" -> RateSpec.Fixed(requireNotNull(r.rate) { "legs[$i].rate.rate is required for FIXED" })
            "FLOATING" -> RateSpec.Floating(
                index = requireNotNull(r.index?.takeIf { it.isNotBlank() }) { "legs[$i].rate.index is required" },
                spread = r.spread ?: BigDecimal.ZERO,
                fixing = FixingConvention(r.fixingLagBusinessDays ?: 2, calendar, r.fixingInArrears ?: false),
                resetFrequency = r.resetFrequency?.let { frequency(i, "rate.resetFrequency", it) } ?: accrual,
                bounds = RateBounds(
                    cap = r.cap,
                    floor = r.floor,
                    appliesTo = r.capFloorAppliesTo?.let { enumOf<CapFloorAppliesTo>(i, "rate.capFloorAppliesTo", it) }
                        ?: CapFloorAppliesTo.INDEX_PLUS_SPREAD,
                ),
                rateUntilFirstFixing = r.rateUntilFirstFixing,
            )
            else -> throw IllegalArgumentException("legs[$i].rate.type '${r.type}' is not FIXED or FLOATING")
        }
    }

    private fun amortisation(i: Int, a: AmortisationDto?): AmortisationSpec = when (a?.type?.trim()?.uppercase()) {
        null, "BULLET" -> AmortisationSpec.Bullet
        "LINEAR" -> AmortisationSpec.Linear
        "ANNUITY" -> AmortisationSpec.Annuity
        "CUSTOM" -> AmortisationSpec.Custom(
            requireNotNull(
                a.schedule?.takeIf {
                    it.isNotEmpty()
                },
            ) { "legs[$i].amortisation.schedule is required for CUSTOM" }
                .sortedBy { it.date }
                .map { requireNotNull(it.amount) { "legs[$i].amortisation.schedule[].amount is required" } },
        )
        else -> throw IllegalArgumentException(
            "legs[$i].amortisation.type '${a.type}' is not BULLET|LINEAR|ANNUITY|CUSTOM",
        )
    }

    private val FREQUENCY = Regex("^([1-9][0-9]{0,2})([DWMY])$")

    /** `ZERO` / `TERM` (one period), or `<n>D|W|M|Y`; also libs-finance's named codes ONCE..ANNUAL. */
    fun frequency(i: Int, field: String, raw: String): Frequency {
        val v = raw.trim().uppercase()
        return Frequency.named(v) ?: when (v) {
            "ZERO", "TERM", "0T" -> Frequency.Zero
            "SEMIANNUAL" -> Frequency.SEMI_ANNUAL
            else -> {
                val m = requireNotNull(FREQUENCY.matchEntire(v)) { "legs[$i].$field '$raw' is not ZERO or <n>D|W|M|Y" }
                val n = m.groupValues[1].toInt()
                when (m.groupValues[2]) {
                    "D" -> Frequency.Days(n)
                    "W" -> Frequency.Weeks(n)
                    "M" -> Frequency.Months(n)
                    else -> Frequency.Months(n * MONTHS_PER_YEAR)
                }
            }
        }
    }

    private const val MONTHS_PER_YEAR = 12

    /**
     * Request bounds (ADR-0344 D8 pricing edge). A real instrument has a handful of legs; 50 years
     * matches the longest swap quote the curves accept. Frequencies are never finer than daily (the
     * grammar has no sub-day unit), and libs-finance caps any schedule at 2,000 periods per leg.
     */
    const val MAX_LEGS = 8
    const val MAX_SPAN_YEARS = 50L

    /** A payment or fixing lag is a handful of business days; each one is walked day by day by the calendar. */
    const val MAX_LAG_BUSINESS_DAYS = 30

    /** Kernel day-count names, with the usual market spellings (`ACT/360`, `30E/360`). */
    fun dayCount(i: Int, raw: String): DayCount {
        val v = raw.trim().uppercase().replace('/', '_').replace(' ', '_')
        val alias = when (v) {
            "ACT_365", "ACT_365F" -> "ACT_365_FIXED"
            "30_360", "30_360_BOND" -> "THIRTY_360_BOND_BASIS"
            "30E_360" -> "THIRTY_E_360"
            "ACT_ACT" -> "ACT_ACT_ISDA"
            else -> v
        }
        return DayCount.entries.firstOrNull { it.name == alias }
            ?: throw IllegalArgumentException("legs[$i].dayCount '$raw' is not one of ${DayCount.entries}")
    }

    private fun roll(i: Int, raw: String?): BusinessDayConvention =
        raw?.let { enumOf<BusinessDayConvention>(i, "businessDayConvention", it) }
            ?: BusinessDayConvention.MODIFIED_FOLLOWING

    /** Calendar codes: a centre (`CERTIS`, `TARGET2`, `WEEKEND-ONLY`) or an ISO currency. Joint when several. */
    fun calendar(codes: List<String>): FinanceCalendar = Calendars.joint(
        codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.ifEmpty { listOf("WEEKEND-ONLY") }.map { code ->
            when (code) {
                "CERTIS" -> BusinessCalendar.certis()
                "TARGET2", "TARGET" -> BusinessCalendar.target2()
                "WEEKEND-ONLY", "WEEKEND_ONLY" -> BusinessCalendar.weekendOnly()
                else -> BusinessCalendar.forCurrency(code)
            }
        },
    )

    private fun date(i: Int, field: String, raw: String): LocalDate = try {
        LocalDate.parse(raw.trim())
    } catch (e: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("legs[$i].$field must be an ISO date", e)
    }

    private inline fun <reified E : Enum<E>> enumOf(i: Int, field: String, raw: String): E =
        enumValues<E>().firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw IllegalArgumentException("legs[$i].$field '$raw' is not one of ${enumValues<E>().joinToString()}")
}
