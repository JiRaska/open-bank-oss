// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.leg

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.finance.calendar.DateRoll
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.schedule.Frequency
import com.openbank.libs.finance.schedule.StubType
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Direction of a leg from the bank's point of view. Every amount the engine produces is SIGNED
 * from the bank's side: [RECEIVE] flows are positive, [PAY] flows negative. An initial notional
 * exchange runs opposite to the leg (the bank pays out the principal of a leg it then receives).
 */
enum class PayReceive(val sign: Int) {
    PAY(-1),
    RECEIVE(1),
}

/**
 * One leg of a treasury instrument (ADR-0344 D2): everything needed to generate its schedule and
 * project its cash flows. Pure data; validation of cross-field consistency happens in the
 * generator, which is the only consumer that knows the full picture.
 *
 * @property rounding the named rounding policy every money amount of this leg is rounded with.
 */
data class LegSpec(
    val payReceive: PayReceive,
    val currency: CurrencyCode,
    val notional: NotionalSpec,
    val schedule: ScheduleSpec,
    val dates: DateRules,
    val rate: RateSpec,
    val dayCount: DayCount,
    val rounding: RoundingPolicy = RoundingPolicy.TREASURY_AMOUNT,
)

/**
 * The unadjusted shape of the schedule.
 *
 * @property accrualFrequency how often interest periods roll.
 * @property paymentFrequency how often payments occur; it may be less frequent than accrual, in
 *   which case one payment aggregates several accrual periods (every payment boundary must be an
 *   accrual boundary). Defaults to the accrual frequency.
 * @property stub where an irregular period goes; it also fixes the generation direction.
 * @property endOfMonth when the anchor (start for forward, end for backward generation) is the last
 *   day of its month, every month-based date is also the last day of its month (ISDA EOM rule).
 */
data class ScheduleSpec(
    val start: LocalDate,
    val end: LocalDate,
    val accrualFrequency: Frequency,
    val paymentFrequency: Frequency = accrualFrequency,
    val stub: StubType = StubType.SHORT_INITIAL,
    val endOfMonth: Boolean = false,
) {
    init {
        require(end.isAfter(start)) { "schedule end $end must be after start $start" }
    }
}

/**
 * Business-day rules for the leg.
 *
 * @property calendar the payment calendar — also used to adjust accrual dates. Use a joint
 *   calendar (`Calendars.joint`) when the flow needs several centres open.
 * @property roll the business-day convention for adjusted dates.
 * @property adjustAccrualPeriod `true`: accrual boundaries are rolled, so interest accrues to the
 *   adjusted date ("adjusted" accrual). `false`: interest accrues to the unadjusted date even when
 *   the payment moves ("unadjusted" accrual, the bond-market default).
 * @property adjustPaymentDate whether the payment date is rolled onto a business day.
 * @property paymentLagBusinessDays business days added to the (possibly rolled) period end.
 */
data class DateRules(
    val calendar: FinanceCalendar,
    val roll: DateRoll = DateRoll.MODIFIED_FOLLOWING,
    val adjustAccrualPeriod: Boolean = true,
    val adjustPaymentDate: Boolean = true,
    val paymentLagBusinessDays: Int = 0,
) {
    init {
        require(paymentLagBusinessDays >= 0) { "payment lag must be non-negative: $paymentLagBusinessDays" }
    }
}

/**
 * Notional, its amortisation, and whether principal actually changes hands.
 *
 * @property initialExchange the notional is exchanged at the start date (deposit placement, the
 *   near leg of an FX swap, a cross-currency swap). Signed opposite to the leg.
 * @property finalExchange principal is repaid: the amortisation flows (or the bullet at maturity)
 *   are emitted. `false` for a pure interest leg such as either side of a single-currency IRS.
 * @property finalExchangeAmount overrides the bullet repayment amount — the far leg of an FX swap,
 *   or a T-bill redeemed at face after being bought at a discount. Only for [AmortisationSpec.Bullet].
 */
data class NotionalSpec(
    val amount: BigDecimal,
    val amortisation: AmortisationSpec = AmortisationSpec.Bullet,
    val initialExchange: Boolean = false,
    val finalExchange: Boolean = true,
    val finalExchangeAmount: BigDecimal? = null,
) {
    init {
        require(amount.signum() > 0) { "notional must be positive: $amount" }
        require(finalExchangeAmount == null || amortisation == AmortisationSpec.Bullet) {
            "finalExchangeAmount applies to a bullet notional only"
        }
    }
}

/** How the notional is repaid across the periods. */
sealed interface AmortisationSpec {
    /** Whole notional outstanding until the last period. */
    data object Bullet : AmortisationSpec

    /** Equal principal per period; the rounding residual lands on the last repayment. */
    data object Linear : AmortisationSpec

    /**
     * Level payment (principal + interest) per period under a [RateSpec.Fixed] rate, using each
     * period's own year fraction; the last principal repays the exact remaining balance.
     */
    data object Annuity : AmortisationSpec

    /** Explicit principal repayment per accrual period; must sum to the notional exactly. */
    data class Custom(val repayments: List<BigDecimal>) : AmortisationSpec
}
