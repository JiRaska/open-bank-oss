// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.cashflow

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.finance.calendar.DateRoll
import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.cashflow.ProjectedCashFlow
import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.CapFloorAppliesTo
import com.openbank.libs.finance.leg.DateRules
import com.openbank.libs.finance.leg.FixingConvention
import com.openbank.libs.finance.leg.NotionalSpec
import com.openbank.libs.finance.leg.RateBounds
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.leg.ScheduleSpec
import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.treasury.domain.model.AmortisationType
import com.openbank.treasury.domain.model.CapFloorBasis
import com.openbank.treasury.domain.model.Frequency
import com.openbank.treasury.domain.model.LegSpec
import com.openbank.treasury.domain.model.PayReceive
import com.openbank.treasury.domain.model.RateType
import com.openbank.treasury.domain.model.RollDirection
import com.openbank.treasury.domain.model.StubType
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.UUID
import com.openbank.libs.finance.leg.LegSpec as FinanceLeg
import com.openbank.libs.finance.leg.PayReceive as FinancePayReceive
import com.openbank.libs.finance.schedule.Frequency as FinanceFrequency
import com.openbank.libs.finance.schedule.StubType as FinanceStub

/** Everything a generation needs besides the legs. */
data class ScheduleContext(
    val dealId: UUID,
    val scheduleVersion: Int,
    /** Payment/accrual calendar of a leg: its own `paymentCalendars` resolved, else the deal's. */
    val calendarFor: (LegSpec) -> FinanceCalendar,
    /** The fixing calendar and lag of a floating leg's index (its reference record). */
    val fixingCalendar: (index: String) -> FinanceCalendar?,
    val fixingLag: (index: String) -> Int?,
    /** The deal's business-day convention; null = unadjusted. */
    val roll: DateRoll,
    val rollDirection: RollDirection,
    val newId: () -> UUID,
)

/** A leg the shared engine cannot express. Mapped to 422 SCHEDULE_ENGINE_UNAVAILABLE. */
class ScheduleEngineUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The treasury side of ADR-0344 D1: turns a deal's [LegSpec]s into cash-flow rows through
 * `openbank-libs-finance` (`ScheduleGenerator` + `CashFlowProjector`). Nothing here generates a
 * date or accrues a cent; it maps types and states:
 *
 * - rates are annual percentages here and decimal fractions in the engine (÷100 in, ×100 out);
 * - amounts become non-negative with a direction from the bank's side;
 * - a floating period without an observed fixing (no fixing source and no forward curve at
 *   generation) is still a ROW — PROJECTED with no amount and its fixing date — because the engine
 *   leaves an unfixed period out of its projection and a ledger must not lose a flow;
 * - fixed and exchange flows are FIXED, a contractual rate until first fixing is FIXED too.
 *
 * A zero-length leg (an FX spot settling on its trade date) is placed over the single day before
 * its value date: the engine needs `end > start`, and an exchange-only leg accrues nothing.
 */
object ScheduleAdapter {
    private val HUNDRED = BigDecimal("100")

    fun generate(legs: List<LegSpec>, ctx: ScheduleContext): List<CashFlow> =
        legs.flatMapIndexed { index, leg -> leg(index, leg, ctx) }

    private fun leg(index: Int, leg: LegSpec, ctx: ScheduleContext): List<CashFlow> {
        val financeLeg = try {
            toFinance(leg, ctx)
        } catch (e: IllegalArgumentException) {
            throw ScheduleEngineUnavailableException("leg $index cannot be scheduled: ${e.message}", e)
        }
        val schedule = try {
            ScheduleGenerator.generate(financeLeg)
        } catch (e: IllegalArgumentException) {
            throw ScheduleEngineUnavailableException("leg $index cannot be scheduled: ${e.message}", e)
        }
        val projection = CashFlowProjector.project(schedule)
        val hash = leg.specHash()
        val projected = projection.flows.map { row(it, index, leg, hash, ctx) }
        val projectedPeriods = projection.flows.filter {
            it.kind ==
                com.openbank.libs.finance.cashflow.CashFlowKind.INTEREST
        }
            .map { it.periodIndex }.toSet()
        val unfixed = if (leg.rate.type == RateType.FLOATING) {
            schedule.periods.filter { it.index !in projectedPeriods }.map { p ->
                CashFlow(
                    flowId = ctx.newId(),
                    dealId = ctx.dealId,
                    scheduleVersion = ctx.scheduleVersion,
                    legIndex = index,
                    periodIndex = p.index,
                    kind = CashFlowKind.INTEREST,
                    direction = incoming(leg),
                    currency = leg.currency,
                    accrualStart = p.accrualStart,
                    accrualEnd = p.accrualEnd,
                    fixingDate = p.fixingDate,
                    paymentDate = p.paymentDate,
                    notional = p.notional,
                    rate = null,
                    rateSource = RateSource.PROJECTED,
                    amount = null,
                    state = CashFlowState.PROJECTED,
                    legHash = hash,
                )
            }
        } else {
            emptyList()
        }
        return (projected + unfixed).sortedWith(compareBy({ it.paymentDate }, { it.periodIndex }, { it.kind }))
    }

    private fun incoming(leg: LegSpec) = if (leg.payReceive ==
        PayReceive.RECEIVE
    ) {
        FlowDirection.IN
    } else {
        FlowDirection.OUT
    }

    private fun row(f: ProjectedCashFlow, index: Int, leg: LegSpec, hash: String, ctx: ScheduleContext): CashFlow {
        val applied = f.rate
        val source = when (applied?.source) {
            null -> RateSource.FIXED
            com.openbank.libs.finance.cashflow.RateSource.FIXED -> RateSource.FIXED
            com.openbank.libs.finance.cashflow.RateSource.OBSERVED -> RateSource.OBSERVED
            com.openbank.libs.finance.cashflow.RateSource.PROJECTED -> RateSource.PROJECTED
            com.openbank.libs.finance.cashflow.RateSource.RATE_UNTIL_FIRST_FIXING -> RateSource.RATE_UNTIL_FIRST_FIXING
        }
        return CashFlow(
            flowId = ctx.newId(),
            dealId = ctx.dealId,
            scheduleVersion = ctx.scheduleVersion,
            legIndex = index,
            periodIndex = f.periodIndex,
            kind = CashFlowKind.valueOf(f.kind.name),
            direction = if (f.amount.signum() < 0) FlowDirection.OUT else FlowDirection.IN,
            currency = leg.currency,
            accrualStart = f.accrualStart,
            accrualEnd = f.accrualEnd,
            fixingDate = f.fixingDate,
            paymentDate = f.paymentDate,
            notional = f.notional,
            rate = applied?.rate?.multiply(HUNDRED)?.stripTrailingZeros()?.let {
                if (it.scale() <
                    0
                ) {
                    it.setScale(0)
                } else {
                    it
                }
            },
            rateSource = source,
            amount = f.amount.abs(),
            state = if (source == RateSource.PROJECTED) CashFlowState.PROJECTED else CashFlowState.FIXED,
            legHash = hash,
        )
    }

    fun toFinance(leg: LegSpec, ctx: ScheduleContext): FinanceLeg {
        leg.validate()
        val calendar = ctx.calendarFor(leg)
        val zeroLength = !leg.accrualEnd.isAfter(leg.accrualStart)
        require(!zeroLength || leg.rate.type == RateType.NONE) {
            "only an exchange-only leg may have a zero-length period"
        }
        val start: LocalDate = if (zeroLength) leg.accrualEnd.minusDays(1) else leg.accrualStart
        return FinanceLeg(
            payReceive = if (leg.payReceive == PayReceive.RECEIVE) FinancePayReceive.RECEIVE else FinancePayReceive.PAY,
            currency = CurrencyCode.of(leg.currency),
            notional = NotionalSpec(
                amount = leg.notional,
                amortisation = when (leg.amortisation.type) {
                    AmortisationType.BULLET -> AmortisationSpec.Bullet
                    AmortisationType.LINEAR -> AmortisationSpec.Linear
                    AmortisationType.ANNUITY -> AmortisationSpec.Annuity
                    AmortisationType.CUSTOM -> AmortisationSpec.Custom(leg.amortisation.schedule.map { it.amount })
                },
                initialExchange = leg.initialNotionalExchange,
                finalExchange = leg.finalNotionalExchange || leg.amortisation.type != AmortisationType.BULLET,
            ),
            schedule = ScheduleSpec(
                start = start,
                end = leg.accrualEnd,
                accrualFrequency = frequency(leg.accrualFrequency),
                paymentFrequency = frequency(leg.paymentFrequency),
                stub = stub(leg.stub, ctx.rollDirection),
            ),
            dates = DateRules(
                calendar = calendar,
                roll = ctx.roll,
                adjustAccrualPeriod = leg.adjustAccrualPeriod,
                adjustPaymentDate = leg.adjustPaymentDate,
                paymentLagBusinessDays = leg.paymentLagBusinessDays,
            ),
            rate = rate(leg, calendar, ctx),
            dayCount = leg.dayCount.kernel,
        )
    }

    private fun rate(leg: LegSpec, calendar: FinanceCalendar, ctx: ScheduleContext): RateSpec {
        val r = leg.rate
        return when (r.type) {
            RateType.NONE -> RateSpec.None
            RateType.FIXED -> RateSpec.Fixed(pct(checkNotNull(r.rate)))
            RateType.FLOATING -> {
                val index = checkNotNull(r.index)
                RateSpec.Floating(
                    index = index,
                    spread = r.spread?.let(::pct) ?: BigDecimal.ZERO,
                    fixing = FixingConvention(
                        lagBusinessDays = r.fixingLagBusinessDays ?: ctx.fixingLag(index) ?: 0,
                        calendar = ctx.fixingCalendar(index) ?: calendar,
                    ),
                    resetFrequency = frequency(r.resetFrequency ?: leg.accrualFrequency),
                    bounds = RateBounds(
                        cap = r.cap?.let(::pct),
                        floor = r.floor?.let(::pct),
                        appliesTo = if (r.capFloorAppliesTo == CapFloorBasis.INDEX) {
                            CapFloorAppliesTo.INDEX
                        } else {
                            CapFloorAppliesTo.INDEX_PLUS_SPREAD
                        },
                    ),
                    rateUntilFirstFixing = r.rateUntilFirstFixing?.let(::pct),
                )
            }
        }
    }

    private fun pct(p: BigDecimal): BigDecimal = p.divide(HUNDRED, MathContext.DECIMAL128)

    private fun frequency(f: Frequency): FinanceFrequency = when (f) {
        Frequency.ONCE -> FinanceFrequency.Zero
        Frequency.DAILY -> FinanceFrequency.Days(1)
        Frequency.WEEKLY -> FinanceFrequency.Weeks(1)
        else -> FinanceFrequency.Months(checkNotNull(f.months))
    }

    /** The deal's roll direction decides where an unspecified stub goes (FROM_START = stub at the end). */
    private fun stub(s: StubType, direction: RollDirection): FinanceStub = when (s) {
        StubType.SHORT_INITIAL -> FinanceStub.SHORT_INITIAL
        StubType.LONG_INITIAL -> FinanceStub.LONG_INITIAL
        StubType.SHORT_FINAL -> FinanceStub.SHORT_FINAL
        StubType.LONG_FINAL -> FinanceStub.LONG_FINAL
        StubType.NONE -> if (direction ==
            RollDirection.FROM_START
        ) {
            FinanceStub.SHORT_FINAL
        } else {
            FinanceStub.SHORT_INITIAL
        }
    }
}
