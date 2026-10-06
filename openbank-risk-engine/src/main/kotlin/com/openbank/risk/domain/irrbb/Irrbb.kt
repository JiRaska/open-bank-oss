// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.CashFlow
import com.openbank.risk.domain.cashflow.CashFlowAggregation
import com.openbank.risk.domain.cashflow.NonMaturityDepositCashFlows
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** The operator-supplied parameters of an IRRBB run: shock sizes per currency and the floor. */
data class IrrbbParameters(
    val shockSizes: Map<String, ShockSizes>,
    val shockSource: String,
    val floor: PostShockFloor?,
    val floorSource: String,
)

/** One currency under one scenario. `deltaEve = shockedPv − basePv`: negative is a LOSS. */
data class CurrencyScenario(
    val currency: String,
    val basePv: BigDecimal,
    val shockedPv: BigDecimal,
    val deltaEve: BigDecimal,
    /** Only for the scenarios [Irrbb.NII_SCENARIOS]; null otherwise. */
    val deltaNii: BigDecimal?,
) {
    /** The d368 sign convention: `EVE_0 − EVE_i`, floored at zero — the loss. */
    val eveLoss: BigDecimal get() = deltaEve.negate().max(BigDecimal.ZERO)
}

data class ScenarioResult(
    val scenario: ShockScenario,
    val currencies: List<CurrencyScenario>,
    /**
     * d368 Annex 2: `max(0; Σ_{c: ΔEVE_i,c > 0} ΔEVE_i,c)` — losses summed, gains dropped. Null
     * when the book is in more than one currency (see [Irrbb.AGGREGATION_NOTE]).
     */
    val aggregateLoss: BigDecimal?,
)

data class IrrbbResult(
    val gaps: List<CurrencyGap>,
    val scenarios: List<ScenarioResult>,
    /** Currencies with positions but no shock sizes configured: no scenario is computed for them. */
    val shockNotConfigured: List<String>,
    /** Currencies with positions but no discounting curve in the set. */
    val unpriced: List<String>,
    val aggregationCurrency: String?,
    /** The scenario with the largest [ScenarioResult.aggregateLoss]; null when not aggregated or no loss. */
    val worstScenario: ShockScenario?,
    val worstLoss: BigDecimal?,
    /** Worst scenario per evaluated currency, by that currency's own loss; absent when it loses under none. */
    val worstByCurrency: Map<String, ShockScenario>,
    /** The treasury money-market deals' share of each currency's gap and ΔEVE; empty without deals. */
    val treasury: List<TreasuryContribution> = emptyList(),
    /** What the figures do not capture ([IrrbbDataGaps]); never empty for a book with loans or deposits. */
    val dataGaps: List<IrrbbDataGap> = emptyList(),
)

/**
 * What a currency's treasury money-market deals contribute: their repricing notional (placements
 * and ČNB deposits as assets, borrowings and lombard as liabilities, magnitudes), their base PV
 * and their ΔEVE per scenario. Already INCLUDED in the gap and scenario figures — this is the
 * breakdown, not an addition. [basePv] and [deltaEve] are null for a currency not evaluated.
 */
data class TreasuryContribution(
    val currency: String,
    val deals: Int,
    val placements: BigDecimal,
    val borrowings: BigDecimal,
    val basePv: BigDecimal?,
    val deltaEve: Map<ShockScenario, BigDecimal>,
)

/**
 * IRRBB for a tied-out snapshot (ADR-0313 phase 1): repricing gap, ΔEVE under the six BCBS d368
 * scenarios, ΔNII over 12 months under the two parallel ones.
 *
 * **ΔEVE.** For each currency with shock sizes and a discounting curve, every flow of the book
 * (behavioural NMD run-off, lending's contractual FIXED schedules, FLOATING loans projected from
 * their index curve, treasury money-market deals as principal plus ACT/360 interest at maturity) is projected and discounted under the base curve set and under the set with
 * that currency's curves shocked. FLOATING flows are RE-PROJECTED on the shocked index curve, so a
 * floating loan's value moves only until its next reset — the repricing behaviour, not an
 * approximation of it. Run-off balance sheet, as d368 prescribes for EVE.
 *
 * **ΔNII.** Constant balance sheet over [NII_HORIZON_MONTHS]: every notional that reprices inside
 * the horizon (see [RepricingGap]) is replaced by the same instrument at a rate moved by the
 * shocked-minus-base discounting zero rate at its repricing tenor, earned from the repricing date
 * to the horizon: `ΔNII = Σ amount · Δr(t) · (1 − t)` with signed amounts (assets earn, liabilities
 * cost) and `t` in years. Full pass-through: the NMD model has no deposit beta, so a deposit that
 * reprices is assumed to reprice by the whole shock. Computed for parallel up and down, the two
 * scenarios d368 prescribes for NII.
 */
object Irrbb {

    const val NII_HORIZON_MONTHS = 12L

    /** Instrument kinds the projection reads; any other kind on a run is reported, never dropped silently. */
    val PROJECTED_KINDS = SnapshotCashFlowProjection.LOAN_KINDS + InstrumentKind.MONEY_MARKET_DEAL
    val NII_SCENARIOS = setOf(ShockScenario.PARALLEL_UP, ShockScenario.PARALLEL_DOWN)
    const val AGGREGATION_NOTE =
        "d368 Annex 2: per scenario the currency losses (EVE_0 − EVE_i > 0) are summed and gains dropped; the " +
            "measure is the maximum over the six scenarios. Summing across currencies needs conversion to one " +
            "reporting currency, which phase 0 does not have, so the aggregate is computed only for a " +
            "single-currency book; a multi-currency book shows per-currency figures only."

    fun compute(
        positions: List<Position>,
        instruments: List<Instrument>,
        asOf: LocalDate,
        curves: CurveSet,
        model: BehaviouralModel,
        params: IrrbbParameters,
    ): IrrbbResult {
        val repricing = RepricingGap.amounts(positions, instruments, asOf, model)
        val bookCurrencies = (
            positions.filter { it.kind == PositionKind.SUB_LEDGER }.map { it.currency } +
                instruments.filter { it.kind in PROJECTED_KINDS }.map { it.currency }
            ).toSortedSet()
        val gaps = bookCurrencies.map { RepricingGap.gap(it, repricing, asOf) }
        val unpriced = bookCurrencies.filter { curves.discountCurveFor(it) == null }
        val notConfigured = bookCurrencies.filter { it !in params.shockSizes }
        val evaluated = bookCurrencies.filter { it !in unpriced && it !in notConfigured }

        val baseFlows = flowsByCurrency(positions, instruments, asOf, curves, model)
        val basePv = evaluated.associateWith { pv(baseFlows[it].orEmpty(), curves, it) }
        val aggregationCurrency = bookCurrencies.singleOrNull()?.takeIf { it in evaluated }

        val scenarios = ShockScenario.entries.map { scenario ->
            val perCurrency = evaluated.map { currency ->
                val sizes = params.shockSizes.getValue(currency)
                val shocked = SupervisoryShocks.shock(curves, currency, scenario, sizes, params.floor)
                val flows = flowsByCurrency(positions, instruments, asOf, shocked, model, currency)[currency].orEmpty()
                val shockedPv = pv(flows, shocked, currency)
                val base = basePv.getValue(currency)
                CurrencyScenario(
                    currency = currency,
                    basePv = base,
                    shockedPv = shockedPv,
                    deltaEve = shockedPv.subtract(base),
                    deltaNii = if (scenario in NII_SCENARIOS) {
                        deltaNii(repricing, currency, asOf, curves, shocked)
                    } else {
                        null
                    },
                )
            }
            val aggregate = aggregationCurrency?.let {
                perCurrency.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.eveLoss) }
            }
            ScenarioResult(scenario, perCurrency, aggregate)
        }
        val worst = scenarios.filter { (it.aggregateLoss?.signum() ?: 0) > 0 }.maxByOrNull { it.aggregateLoss!! }
        val worstByCurrency = evaluated.mapNotNull { c ->
            scenarios.map { s -> s.scenario to s.currencies.first { it.currency == c }.eveLoss }
                .filter { it.second.signum() > 0 }
                .maxByOrNull { it.second }
                ?.let { c to it.first }
        }.toMap()
        return IrrbbResult(
            gaps = gaps,
            scenarios = scenarios,
            shockNotConfigured = notConfigured,
            unpriced = unpriced - notConfigured.toSet(),
            aggregationCurrency = aggregationCurrency,
            worstScenario = worst?.scenario,
            worstLoss = worst?.aggregateLoss,
            worstByCurrency = worstByCurrency,
            treasury = treasury(instruments, curves, evaluated, params),
            dataGaps = IrrbbDataGaps.of(positions, instruments, curves, baseFlows, evaluated),
        )
    }

    private fun treasury(
        instruments: List<Instrument>,
        curves: CurveSet,
        evaluated: List<String>,
        params: IrrbbParameters,
    ): List<TreasuryContribution> = instruments
        .filter { it.kind == InstrumentKind.MONEY_MARKET_DEAL && it.outstanding.signum() != 0 }
        .groupBy { it.currency }
        .toSortedMap()
        .map { (currency, deals) ->
            val flows = deals.flatMap { SnapshotCashFlowProjection.moneyMarketFlows(it) }
            val priced = currency in evaluated
            val base = if (priced) pv(flows, curves, currency) else null
            TreasuryContribution(
                currency = currency,
                deals = deals.size,
                placements = deals.filter { it.outstanding.signum() > 0 }
                    .fold(BigDecimal.ZERO) { a, d -> a.add(d.outstanding) },
                borrowings = deals.filter { it.outstanding.signum() < 0 }
                    .fold(BigDecimal.ZERO) { a, d -> a.add(d.outstanding.negate()) },
                basePv = base,
                deltaEve = if (base == null) {
                    emptyMap()
                } else {
                    ShockScenario.entries.associateWith { scenario ->
                        val shocked = SupervisoryShocks.shock(
                            curves,
                            currency,
                            scenario,
                            params.shockSizes.getValue(currency),
                            params.floor,
                        )
                        pv(flows, shocked, currency).subtract(base)
                    }
                },
            )
        }

    /** `Σ amount · (r_shocked(t) − r_base(t)) · (1 − t)` over repricings inside the horizon. */
    fun deltaNii(
        repricing: List<RepricingAmount>,
        currency: String,
        asOf: LocalDate,
        base: CurveSet,
        shocked: CurveSet,
    ): BigDecimal {
        val baseCurve = requireNotNull(base.discountCurveFor(currency))
        val shockedCurve = requireNotNull(shocked.discountCurveFor(currency))
        val horizon = asOf.plusMonths(NII_HORIZON_MONTHS)
        val horizonYears = Curve.yearFraction(asOf, horizon)
        return repricing
            .filter { it.currency == currency && !it.date.isAfter(horizon) }
            .fold(BigDecimal.ZERO) { acc, r ->
                val date = maxOf(r.date, asOf)
                val t = Curve.yearFraction(asOf, date)
                val dr = shockedCurve.zeroRate(date).subtract(baseCurve.zeroRate(date), BigMath.MC)
                val earning = horizonYears.subtract(t).divide(horizonYears, BigMath.MC)
                acc.add(r.amount.multiply(dr, BigMath.MC).multiply(earning, BigMath.MC), BigMath.MC)
            }
            .setScale(scale(currency), RoundingMode.HALF_EVEN)
    }

    private fun pv(flows: List<CashFlow>, set: CurveSet, currency: String): BigDecimal =
        CashFlowAggregation.presentValue(flows, requireNotNull(set.discountCurveFor(currency)), scale(currency))

    private fun scale(currency: String) = CurrencyCode.of(currency).defaultFractionDigits

    private fun flowsByCurrency(
        positions: List<Position>,
        instruments: List<Instrument>,
        asOf: LocalDate,
        curves: CurveSet,
        model: BehaviouralModel,
        only: String? = null,
    ): Map<String, List<CashFlow>> {
        val deposits = positions
            .filter { it.kind == PositionKind.SUB_LEDGER && (only == null || it.currency == only) }
            .flatMap { NonMaturityDepositCashFlows.expand(it.amount.negate(), it.currency, asOf, model) }
        val loans = instruments
            .filter { it.kind in SnapshotCashFlowProjection.LOAN_KINDS && (only == null || it.currency == only) }
            .flatMap { SnapshotCashFlowProjection.loanFlows(it, curves) }
        // Fixed-rate to maturity: the flows do not depend on the curves, only their discounting does.
        val deals = instruments
            .filter { it.kind == InstrumentKind.MONEY_MARKET_DEAL && (only == null || it.currency == only) }
            .flatMap { SnapshotCashFlowProjection.moneyMarketFlows(it) }
        return (deposits + loans + deals).groupBy { it.currency }
    }
}
