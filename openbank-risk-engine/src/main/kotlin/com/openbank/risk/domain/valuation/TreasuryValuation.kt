// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.valuation

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.cashflow.ProjectedCashFlow
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.pricing.DiscountCurve
import com.openbank.libs.finance.pricing.LegPricer
import com.openbank.libs.finance.pricing.PRICING_MC
import com.openbank.libs.finance.pricing.Sensitivities
import com.openbank.libs.finance.pricing.Valuation
import com.openbank.libs.finance.schedule.Schedule
import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveIndex
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * The market a valuation runs on: one curve per index (all as of [asOf]) and the reporting-currency
 * value of one unit of each foreign currency ([fxToReporting]; the reporting currency itself is 1).
 */
data class MarketState(
    val asOf: LocalDate,
    val curves: Map<CurveIndex, Curve>,
    val reportingCurrency: String,
    val fxToReporting: Map<String, BigDecimal>,
) {
    fun discount(currency: String): Curve? = CurveIndex.discountingFor(currency)?.let { curves[it] }

    fun fx(currency: String): BigDecimal? = if (currency ==
        reportingCurrency
    ) {
        BigDecimal.ONE
    } else {
        fxToReporting[currency]
    }
}

/** One leg's figures; [pv] and [accrued] in the leg currency, [pvReporting] converted. */
data class LegValuation(
    val legIndex: Int,
    val currency: String,
    val pv: BigDecimal,
    val pvReporting: BigDecimal,
    val accrued: BigDecimal,
    val accruedReporting: BigDecimal,
)

/** One deal's valuation, or the reason it could not be valued ([unpriced] non-null, figures null). */
data class DealValuation(
    val dealId: UUID,
    val bookId: String?,
    val kind: DealKind,
    val flowSource: FlowSource,
    val legs: List<LegValuation>,
    val unpriced: String?,
) {
    val dirty: BigDecimal? get() = if (unpriced !=
        null
    ) {
        null
    } else {
        legs.fold(BigDecimal.ZERO) { a, l -> a.add(l.pvReporting) }
    }
    val accrued: BigDecimal? get() =
        if (unpriced != null) null else legs.fold(BigDecimal.ZERO) { a, l -> a.add(l.accruedReporting) }
    val clean: BigDecimal? get() = dirty?.subtract(accrued)
}

/** A key-rate bucket's DV01 in the reporting currency. */
data class BucketSensitivity(val bucket: String, val tenor: Period, val dv01: BigDecimal)

/** Curve and FX sensitivities of a set of deals, in the reporting currency. */
data class SensitivityResult(
    val pv01: BigDecimal,
    val dv01: List<BucketSensitivity>,
    val fxDelta: Map<String, BigDecimal>,
)

/**
 * Values deals on a [MarketState] with libs-finance (see [ValuationModel] for the conventions). A
 * deal is PREPARED once — schedules generated, stored facts indexed — and then revalued under any
 * curve bump or historical scenario without regenerating its schedule.
 */
class TreasuryValuator(private val market: MarketState) {

    /** A prepared deal: per leg, the slot every revaluation reuses. */
    class Prepared internal constructor(
        val deal: ValuableDeal,
        internal val slots: List<Slot>,
        val flowSource: FlowSource,
    )

    internal class Slot(
        val legIndex: Int,
        val currency: String,
        val leg: LegSpec?,
        val schedule: Schedule?,
        val projectionIndex: CurveIndex?,
        val facts: Map<Pair<Int, CashFlowKind>, StoredCashFlow>,
        val cancelled: Set<Pair<Int, CashFlowKind>>,
        val extras: List<StoredCashFlow>,
    )

    /** Either a prepared deal, or why it cannot be valued on this market. */
    sealed interface Preparation {
        data class Ready(val prepared: Prepared) : Preparation

        data class Unpriced(val reason: String) : Preparation
    }

    @Suppress("ReturnCount")
    fun prepare(deal: ValuableDeal): Preparation {
        val stored = deal.stored?.flows.orEmpty().groupBy { it.legIndex }
        val legIndices = (deal.legs.indices.toSet() + stored.keys).sorted()
        val slots = mutableListOf<Slot>()
        for (i in legIndices) {
            val leg = deal.legs.getOrNull(i)
            val rows = stored[i].orEmpty()
            val currency = leg?.currency?.code ?: rows.first().currency
            if (market.discount(currency) == null) return Preparation.Unpriced("no discounting curve for $currency")
            if (market.fx(currency) == null) {
                return Preparation.Unpriced("no FX fixing in effect for $currency against ${market.reportingCurrency}")
            }
            val projection = (leg?.rate as? RateSpec.Floating)?.let { f ->
                CurveIndex.forIndexName(f.index)?.takeIf { it in market.curves }
                    ?: return Preparation.Unpriced("no projection curve for index ${f.index}")
            }
            val facts = rows.filter { it.isFact && !it.cancelled }.associateBy { it.periodIndex to it.kind }
            if (leg == null && rows.any { it.amount == null && !it.cancelled }) {
                return Preparation.Unpriced("stored flow of leg $i has no amount and the deal publishes no legs")
            }
            slots += Slot(
                legIndex = i,
                currency = currency,
                leg = leg,
                schedule = leg?.let(ScheduleGenerator::generate),
                projectionIndex = projection,
                facts = facts,
                cancelled = rows.filter { it.cancelled }.map { it.periodIndex to it.kind }.toSet(),
                extras = if (leg == null) rows.filter { !it.cancelled } else emptyList(),
            )
        }
        val source = if (deal.stored != null) FlowSource.STORED else FlowSource.DERIVED
        return Preparation.Ready(Prepared(deal, slots, source))
    }

    /**
     * The flows of [slot] under the discount curve [discount] (possibly bumped): derived flows are
     * projected off the index curve moved by the SAME factor as [discount] moved from base, then
     * every stored FACT replaces its derived row and a cancelled row is dropped.
     */
    internal fun flows(
        slot: Slot,
        discount: DiscountCurve,
        marketState: MarketState = market,
    ): List<ProjectedCashFlow> {
        val derived = slot.schedule?.let { schedule ->
            val base = marketState.discount(slot.currency)!!
            val projection = slot.projectionIndex?.let { idx ->
                val index = marketState.curves.getValue(idx)
                (if (discount === base) index else CoShiftedCurve(index, discount, base)).impliedForwards()
            }
            CashFlowProjector.project(schedule, forward = projection).requireComplete()
        }.orEmpty()
        val replaced = derived.mapNotNull { f ->
            val key = f.periodIndex to f.kind
            when {
                key in slot.cancelled -> null
                key in slot.facts -> slot.facts.getValue(key).let { s ->
                    f.copy(paymentDate = s.paymentDate, amount = s.signedAmount!!)
                }
                else -> f
            }
        }
        val derivedKeys = derived.map { it.periodIndex to it.kind }.toSet()
        val leftoverFacts = slot.facts.filterKeys { it !in derivedKeys }.values
        return replaced + (slot.extras + leftoverFacts).distinct().map { it.toProjected() }
    }

    private fun StoredCashFlow.toProjected() = ProjectedCashFlow(
        periodIndex = periodIndex,
        kind = kind,
        accrual = if (accrualStart != null && accrualEnd != null) {
            com.openbank.libs.finance.schedule.DateRange(accrualStart, accrualEnd)
        } else {
            null
        },
        paymentDate = paymentDate,
        currency = com.openbank.libs.domain.money.CurrencyCode.of(currency),
        notional = BigDecimal.ZERO,
        rate = null,
        amount = signedAmount!!,
    )

    /** PV of one slot in its own currency, every flow paid strictly after as-of. */
    internal fun slotPv(slot: Slot, discount: DiscountCurve, marketState: MarketState = market): BigDecimal =
        LegPricer.presentValue(flows(slot, discount, marketState), discount, marketState.asOf)

    fun value(prepared: Prepared): DealValuation = value(prepared, market)

    /** Values [prepared] on [state] — the base market, or a historical scenario of it. */
    fun value(prepared: Prepared, state: MarketState): DealValuation {
        val legs = prepared.slots.map { slot ->
            val discount = state.discount(slot.currency)!!
            val fx = state.fx(slot.currency)!!
            val flows = flows(slot, discount, state)
            val pv = LegPricer.presentValue(flows, discount, state.asOf)
            val accrued = accrued(slot, flows, state.asOf)
            LegValuation(
                slot.legIndex,
                slot.currency,
                pv,
                pv.multiply(fx, PRICING_MC),
                accrued,
                accrued.multiply(fx, PRICING_MC),
            )
        }
        val d = prepared.deal
        return DealValuation(d.dealId, d.bookId, d.kind, prepared.flowSource, legs, null)
    }

    /** Book PV in the reporting currency of already prepared deals on [state]. */
    fun bookPv(prepared: List<Prepared>, state: MarketState): BigDecimal = prepared.fold(BigDecimal.ZERO) { a, p ->
        a.add(
            p.slots.fold(BigDecimal.ZERO) { b, s ->
                b.add(slotPv(s, state.discount(s.currency)!!, state).multiply(state.fx(s.currency)!!, PRICING_MC))
            },
        )
    }

    private fun accrued(slot: Slot, flows: List<ProjectedCashFlow>, asOf: LocalDate): BigDecimal {
        val dayCount = slot.leg?.dayCount ?: DayCount.ACT_360
        return flows.filter { f ->
            f.kind == CashFlowKind.INTEREST &&
                f.paymentDate.isAfter(asOf) &&
                f.accrualStart?.let { !it.isAfter(asOf) } == true &&
                f.accrualEnd?.isAfter(asOf) == true
        }.fold(BigDecimal.ZERO) { a, f ->
            val whole = dayCount.yearFraction(f.accrualStart!!, f.accrualEnd!!).toBigDecimal(PRICING_MC)
            if (whole.signum() == 0) {
                a
            } else {
                val part = dayCount.yearFraction(f.accrualStart!!, asOf).toBigDecimal(PRICING_MC)
                a.add(f.amount.multiply(part, PRICING_MC).divide(whole, PRICING_MC), PRICING_MC)
            }
        }
    }

    /**
     * PV01, key-rate DV01 and FX delta of [prepared], in the reporting currency. Curve figures use
     * libs-finance [Sensitivities] per currency (a currency's legs are one [Valuation] of its
     * discount curve) and are summed after conversion — a simultaneous shift of every currency is
     * that sum, because no leg reads two currencies' curves.
     */
    fun sensitivities(prepared: List<Prepared>, buckets: BucketSet): SensitivityResult {
        val byCurrency = prepared.flatMap { it.slots }.groupBy { it.currency }.toSortedMap()
        var pv01 = BigDecimal.ZERO
        val dv01 = buckets.tenors.map { BigDecimal.ZERO }.toMutableList()
        val fxDelta = sortedMapOf<String, BigDecimal>()
        byCurrency.forEach { (ccy, slots) ->
            val base = market.discount(ccy)!!
            val fx = market.fx(ccy)!!
            val valuation =
                Valuation { curve -> slots.fold(BigDecimal.ZERO) { a, s -> a.add(slotPv(s, curve), PRICING_MC) } }
            pv01 = pv01.add(Sensitivities.pv01(valuation, base).multiply(fx, PRICING_MC), PRICING_MC)
            Sensitivities.bucketedDv01(valuation, base, buckets.tenors).forEachIndexed { i, b ->
                dv01[i] = dv01[i].add(b.dv01.multiply(fx, PRICING_MC), PRICING_MC)
            }
            if (ccy != market.reportingCurrency) {
                val pvReporting = valuation.presentValue(base).multiply(fx, PRICING_MC)
                fxDelta[ccy] = pvReporting.multiply(ValuationModel.ONE_PERCENT, PRICING_MC)
            }
        }
        return SensitivityResult(
            pv01,
            buckets.tenors.mapIndexed { i, t -> BucketSensitivity(buckets.label(t), t, dv01[i]) },
            fxDelta,
        )
    }
}

/**
 * [index] moved by the factor [bumped] moved from [unbumped]: `DF'(t) = DF(t) · B(t) / U(t)`. For
 * a libs-finance `ShiftedCurve` bump that factor is exactly `exp(−shift·w(t)·t)`, so the projection
 * curve receives the identical zero-rate shift as the discount curve.
 */
internal class CoShiftedCurve(
    private val index: DiscountCurve,
    private val bumped: DiscountCurve,
    private val unbumped: DiscountCurve,
) : DiscountCurve {
    override val valuationDate: LocalDate get() = index.valuationDate

    override fun discountFactor(date: LocalDate): BigDecimal = index.discountFactor(date)
        .multiply(bumped.discountFactor(date), PRICING_MC)
        .divide(unbumped.discountFactor(date), PRICING_MC)
}
