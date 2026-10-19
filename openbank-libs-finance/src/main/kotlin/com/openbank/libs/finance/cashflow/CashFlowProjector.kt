// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.cashflow

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.pricing.ForwardCurve
import com.openbank.libs.finance.schedule.DateRange
import com.openbank.libs.finance.schedule.Schedule
import com.openbank.libs.finance.schedule.SchedulePeriod
import java.math.BigDecimal
import java.time.LocalDate

/** The cash-flow kinds of the ADR-0344 D4 ledger. */
enum class CashFlowKind { PRINCIPAL, INTEREST, FEE, NOTIONAL_EXCHANGE, PREMIUM }

/** Where a flow's rate came from. */
enum class RateSource {
    FIXED,

    /** An observed fixing from the [FixingSource]. */
    OBSERVED,

    /** Projected from a [ForwardCurve]: a projection, never a fact. */
    PROJECTED,

    /** The contractual first-period rate (`rateUntilFirstFixing`). */
    RATE_UNTIL_FIRST_FIXING,
}

/**
 * The rate an interest flow accrued at.
 *
 * @property indexValue the raw index value used (observed or projected), before spread and bounds;
 *   `null` for a fixed rate and for a contractual first-period rate.
 */
data class AppliedRate(
    val rate: BigDecimal,
    val source: RateSource,
    val fixingDate: LocalDate?,
    val indexValue: BigDecimal? = null,
)

/**
 * One projected cash flow. [amount] is SIGNED from the bank's side (receive > 0, pay < 0) and
 * rounded under the leg's rounding policy.
 *
 * @property periodIndex the schedule period this flow belongs to; `-1` for the initial exchange.
 * @property accrual the accrual period of an interest flow; `null` for principal and exchanges.
 * @property notional the notional the flow is computed on (outstanding notional for interest).
 * @property rate the applied rate of an interest flow; `null` otherwise.
 */
data class ProjectedCashFlow(
    val periodIndex: Int,
    val kind: CashFlowKind,
    val accrual: DateRange?,
    val paymentDate: LocalDate,
    val currency: CurrencyCode,
    val notional: BigDecimal,
    val rate: AppliedRate?,
    val amount: BigDecimal,
) {
    val accrualStart: LocalDate? get() = accrual?.start
    val accrualEnd: LocalDate? get() = accrual?.end
    val fixingDate: LocalDate? get() = rate?.fixingDate
    val rateSource: RateSource? get() = rate?.source
}

/** A fixing the projection needed and could not obtain from any source. */
data class MissingFixing(val periodIndex: Int, val index: String, val fixingDate: LocalDate)

/** An observation actually used by a projection — the "fixing set" hashed into a schedule version. */
data class UsedFixing(val index: String, val fixingDate: LocalDate, val value: BigDecimal)

/**
 * Result of a projection. A floating period with no observation, no forward curve and no
 * contractual rate is never silently zero: the projection is [Incomplete] and names it.
 */
sealed interface CashFlowProjection {
    /** Every flow that could be determined, in payment order. */
    val flows: List<ProjectedCashFlow>
    val fixingsUsed: List<UsedFixing>

    data class Complete(override val flows: List<ProjectedCashFlow>, override val fixingsUsed: List<UsedFixing>) :
        CashFlowProjection

    /** [flows] omits the interest of every period in [missing]; principal flows are still present. */
    data class Incomplete(
        override val flows: List<ProjectedCashFlow>,
        override val fixingsUsed: List<UsedFixing>,
        val missing: List<MissingFixing>,
    ) : CashFlowProjection

    /** The flows, or an exception naming every missing fixing. */
    fun requireComplete(): List<ProjectedCashFlow> = when (this) {
        is Complete -> flows
        is Incomplete -> error("missing fixings: $missing")
    }
}

/** Observed index fixings, e.g. backed by the market-data port. */
fun interface FixingSource {
    /** The observed value of [index] on [fixingDate], or `null` when none has been published. */
    fun observed(index: String, fixingDate: LocalDate): BigDecimal?

    companion object {
        val NONE: FixingSource = FixingSource { _, _ -> null }

        /** A source over a fixed map keyed by (index, fixing date). */
        fun of(fixings: Map<Pair<String, LocalDate>, BigDecimal>): FixingSource =
            FixingSource { index, date -> fixings[index to date] }
    }
}

/**
 * Projects the cash flows of a generated [Schedule].
 *
 * Floating rate resolution per period, in order:
 * 1. period 0 with `rateUntilFirstFixing` set → that contractual rate (it is a fact, so a forward
 *    curve must not override it);
 * 2. an observed fixing on the period's fixing date → [RateSource.OBSERVED];
 * 3. a [ForwardCurve], when given → [RateSource.PROJECTED] over the accrual period;
 * 4. otherwise the period is reported as a [MissingFixing].
 * Cap and floor apply to observed and projected rates per `RateBounds.appliesTo`; a contractual
 * first-period rate is already all-in and is not bounded again.
 */
object CashFlowProjector {
    fun project(
        schedule: Schedule,
        fixings: FixingSource = FixingSource.NONE,
        forward: ForwardCurve? = null,
    ): CashFlowProjection {
        val leg = schedule.leg
        val flows = mutableListOf<ProjectedCashFlow>()
        val used = mutableListOf<UsedFixing>()
        val missing = mutableListOf<MissingFixing>()
        if (leg.notional.initialExchange) flows += initialExchange(schedule)
        for (period in schedule.periods) {
            when (val resolved = resolveRate(leg, period, fixings, forward)) {
                is Resolved.Rate -> {
                    flows += interest(leg, period, resolved.applied)
                    resolved.used?.let { used += it }
                }
                is Resolved.Missing -> missing += resolved.missing
                Resolved.NoInterest -> Unit
            }
            principal(schedule, period)?.let { flows += it }
        }
        return if (missing.isEmpty()) {
            CashFlowProjection.Complete(flows, used)
        } else {
            CashFlowProjection.Incomplete(flows, used, missing)
        }
    }

    private sealed interface Resolved {
        data class Rate(val applied: AppliedRate, val used: UsedFixing?) : Resolved
        data class Missing(val missing: MissingFixing) : Resolved
        data object NoInterest : Resolved
    }

    private fun resolveRate(
        leg: LegSpec,
        period: SchedulePeriod,
        fixings: FixingSource,
        forward: ForwardCurve?,
    ): Resolved = when (val rate = leg.rate) {
        RateSpec.None -> Resolved.NoInterest
        is RateSpec.Fixed -> Resolved.Rate(AppliedRate(rate.rate, RateSource.FIXED, null), null)
        is RateSpec.Floating -> resolveFloating(leg, rate, period, fixings, forward)
    }

    private fun resolveFloating(
        leg: LegSpec,
        rate: RateSpec.Floating,
        period: SchedulePeriod,
        fixings: FixingSource,
        forward: ForwardCurve?,
    ): Resolved {
        val fixingDate = requireNotNull(period.fixingDate) { "floating period ${period.index} has no fixing date" }
        val contractual = rate.rateUntilFirstFixing
        if (period.index == 0 && contractual != null) {
            return Resolved.Rate(AppliedRate(contractual, RateSource.RATE_UNTIL_FIRST_FIXING, fixingDate), null)
        }
        val observed = fixings.observed(rate.index, fixingDate)
        if (observed != null) {
            val applied =
                AppliedRate(rate.bounds.apply(observed, rate.spread), RateSource.OBSERVED, fixingDate, observed)
            return Resolved.Rate(applied, UsedFixing(rate.index, fixingDate, observed))
        }
        if (forward != null) {
            val projected = forward.forwardRate(period.accrualStart, period.accrualEnd, leg.dayCount)
            val applied =
                AppliedRate(rate.bounds.apply(projected, rate.spread), RateSource.PROJECTED, fixingDate, projected)
            return Resolved.Rate(applied, null)
        }
        return Resolved.Missing(MissingFixing(period.index, rate.index, fixingDate))
    }

    private fun interest(leg: LegSpec, period: SchedulePeriod, applied: AppliedRate): ProjectedCashFlow {
        val amount = Accrual.interest(leg, period.notional, applied.rate, period.yearFraction)
        return ProjectedCashFlow(
            periodIndex = period.index,
            kind = CashFlowKind.INTEREST,
            accrual = period.accrual,
            paymentDate = period.paymentDate,
            currency = leg.currency,
            notional = period.notional,
            rate = applied,
            amount = amount.multiply(BigDecimal(leg.payReceive.sign)),
        )
    }

    private fun initialExchange(schedule: Schedule): ProjectedCashFlow {
        val leg = schedule.leg
        val amount = leg.rounding.round(leg.notional.amount, leg.currency)
        return ProjectedCashFlow(
            periodIndex = -1,
            kind = CashFlowKind.NOTIONAL_EXCHANGE,
            accrual = null,
            paymentDate = schedule.initialExchangeDate,
            currency = leg.currency,
            notional = leg.notional.amount,
            rate = null,
            amount = amount.multiply(BigDecimal(-leg.payReceive.sign)),
        )
    }

    /**
     * The repayment at the end of [period], when the leg repays principal. A leg with no interest
     * (an FX or discount-instrument cash leg) repays as [CashFlowKind.NOTIONAL_EXCHANGE]; an
     * interest-bearing leg as [CashFlowKind.PRINCIPAL].
     */
    private fun principal(schedule: Schedule, period: SchedulePeriod): ProjectedCashFlow? {
        val leg = schedule.leg
        if (!leg.notional.finalExchange) return null
        val isLast = period.index == schedule.periods.last().index
        val raw = if (isLast) leg.notional.finalExchangeAmount ?: period.principal else period.principal
        if (raw.signum() == 0) return null
        val kind = if (leg.rate == RateSpec.None) CashFlowKind.NOTIONAL_EXCHANGE else CashFlowKind.PRINCIPAL
        return ProjectedCashFlow(
            periodIndex = period.index,
            kind = kind,
            accrual = null,
            paymentDate = period.paymentDate,
            currency = leg.currency,
            notional = period.notional,
            rate = null,
            amount = leg.rounding.round(raw, leg.currency).multiply(BigDecimal(leg.payReceive.sign)),
        )
    }
}
