// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.libs.finance.cashflow.CashFlowKind
import com.openbank.libs.finance.cashflow.CashFlowProjector
import com.openbank.libs.finance.cashflow.ScheduleHash
import com.openbank.libs.finance.leg.RateSpec
import com.openbank.libs.finance.pricing.PRICING_MC
import com.openbank.libs.finance.schedule.ScheduleGenerator
import com.openbank.risk.application.port.`in`.BookTotals
import com.openbank.risk.application.port.`in`.FxPositionAnalysis
import com.openbank.risk.application.port.`in`.FxPositionLine
import com.openbank.risk.application.port.`in`.PricingCommand
import com.openbank.risk.application.port.`in`.PricingResult
import com.openbank.risk.application.port.`in`.SnapshotSensitivity
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.`in`.SnapshotValuation
import com.openbank.risk.application.port.`in`.ValuationContext
import com.openbank.risk.application.port.`in`.ValuationUseCase
import com.openbank.risk.application.port.`in`.VarAnalysis
import com.openbank.risk.application.port.out.CurveSetNotFoundException
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.application.port.out.TreasuryCashFlowStore
import com.openbank.risk.application.port.out.TreasuryDealBook
import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import com.openbank.risk.domain.cashflow.CashFlow
import com.openbank.risk.domain.curve.CurveBootstrap
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TreasuryDeal
import com.openbank.risk.domain.model.TreasuryLegs
import com.openbank.risk.domain.valuation.BucketSet
import com.openbank.risk.domain.valuation.DealKind
import com.openbank.risk.domain.valuation.DealValuation
import com.openbank.risk.domain.valuation.FlowSource
import com.openbank.risk.domain.valuation.HistoricalMarket
import com.openbank.risk.domain.valuation.HistoricalVar
import com.openbank.risk.domain.valuation.MarketState
import com.openbank.risk.domain.valuation.ReportProvenance
import com.openbank.risk.domain.valuation.TreasuryValuator
import com.openbank.risk.domain.valuation.ValuableDeal
import com.openbank.risk.domain.valuation.ValuationModel
import com.openbank.risk.domain.valuation.forIndexName
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Historical-simulation settings (`openbank.risk.var.*`); configuration, never code defaults. */
data class VarSettings(val minimumScenarios: Int, val windowDays: Int) {
    init {
        require(minimumScenarios >= 1) { "openbank.risk.var.minimum-scenarios must be at least 1" }
        require(windowDays >= 1) { "openbank.risk.var.window-days must be at least 1" }
    }
}

/**
 * Valuation, sensitivity, FX position and VaR of the bank's own deals (ADR-0344 D8), derived on
 * request and never stored. Read-only and off the money path (ADR-0313 D1): it reads the deal book,
 * treasury's stored flows, curve sets and FX fixings, and writes nothing.
 *
 * Scope rule (ADR-0344 D2): snapshot reads value ACTUAL deals only; PLANNED deals feed the liquidity
 * forecast only ([plannedFlows]); HYPOTHETICAL deals are never read from the book — pre-trade
 * pricing takes its legs in the request ([price]).
 *
 * The data-gap rule (as ADR-0097): a book figure over a partial book is not a figure. Any deal that
 * cannot be valued (no curve, no FX rate, a stored flow without an amount) leaves the BOOK totals,
 * PV01 and VaR unstated with the reason, while every valuable deal is still listed.
 */
@Suppress("TooManyFunctions") // one function per read the D8 endpoints serve, plus their shared loading
class TreasuryValuationService(
    private val snapshots: SnapshotUseCase,
    private val curveSets: CurveSetRepository,
    private val deals: TreasuryDealBook,
    private val storedFlows: TreasuryCashFlowStore,
    private val fixings: ReportingFixings,
    private val varSettings: VarSettings,
) : ValuationUseCase {

    override suspend fun price(command: PricingCommand): PricingResult {
        require(command.legs.isNotEmpty()) { "field 'legs' must hold at least one leg" }
        val set = curveSet(command.asOf, command.curveSetId)
        val currencies = command.legs.map { it.currency.code }.toSet() + command.reportingCurrency
        val fx = fixings.inEffect(currencies, command.asOf)
        val market = market(set, command.reportingCurrency, fx)
        val valuator = TreasuryValuator(market)
        val deal = ValuableDeal(PRICING_DEAL_ID, null, DealKind.HYPOTHETICAL, command.legs)
        val prepared = when (val p = valuator.prepare(deal)) {
            is TreasuryValuator.Preparation.Ready -> p.prepared
            is TreasuryValuator.Preparation.Unpriced -> throw IllegalArgumentException(
                "legs cannot be priced: ${p.reason}",
            )
        }
        return PricingResult(
            asOf = command.asOf,
            curveSetId = set.id,
            reportingCurrency = command.reportingCurrency,
            provenance = if (set.provenance ==
                Provenance.SYNTHETIC
            ) {
                ReportProvenance.SYNTHETIC
            } else {
                ReportProvenance.INTRADAY
            },
            valuation = valuator.value(prepared),
            sensitivity = valuator.sensitivities(listOf(prepared), BucketSet.IRRBB_STANDARD),
            fxRates = fx,
        )
    }

    override suspend fun valuation(runId: UUID, bookId: String?, curveSetId: UUID?): SnapshotValuation {
        val book = load(runId, bookId, curveSetId)
        val values = book.prepared.map { book.valuator.value(it) } + book.unpriced
        val sorted = values.sortedBy { it.dealId }
        val total = if (book.gap != null) {
            null
        } else {
            val dirty = sorted.fold(BigDecimal.ZERO) { a, d -> a.add(d.dirty!!) }
            val accrued = sorted.fold(BigDecimal.ZERO) { a, d -> a.add(d.accrued!!) }
            BookTotals(dirty, accrued, dirty.subtract(accrued), dirty)
        }
        return SnapshotValuation(book.context, sorted, total, book.gap)
    }

    override suspend fun sensitivity(
        runId: UUID,
        bookId: String?,
        curveSetId: UUID?,
        buckets: BucketSet,
    ): SnapshotSensitivity {
        val book = load(runId, bookId, curveSetId)
        val result = if (book.gap != null) null else book.valuator.sensitivities(book.prepared, buckets)
        return SnapshotSensitivity(book.context, buckets, result, book.gap)
    }

    override suspend fun fxPosition(runId: UUID, bookId: String?, curveSetId: UUID?): FxPositionAnalysis {
        val book = load(runId, bookId, curveSetId)
        val legs = book.prepared.flatMap { book.valuator.value(it).legs }
        val lines = legs.groupBy { it.currency }.toSortedMap().map { (ccy, ls) ->
            val pv = ls.fold(BigDecimal.ZERO) { a, l -> a.add(l.pv) }
            val reporting = ls.fold(BigDecimal.ZERO) { a, l -> a.add(l.pvReporting) }
            FxPositionLine(
                currency = ccy,
                position = pv,
                positionReporting = reporting,
                fxRate = book.market.fx(ccy)!!,
                fxDelta = if (ccy == book.market.reportingCurrency) {
                    BigDecimal.ZERO
                } else {
                    reporting.multiply(ValuationModel.ONE_PERCENT, PRICING_MC)
                },
            )
        }
        return FxPositionAnalysis(book.context, lines, book.gap)
    }

    override suspend fun valueAtRisk(
        runId: UUID,
        bookId: String?,
        confidence: BigDecimal,
        horizonDays: Int,
    ): VarAnalysis {
        require(confidence > BigDecimal("0.5") && confidence < BigDecimal.ONE) {
            "query parameter 'confidence' must be in (0.5, 1)"
        }
        require(horizonDays in 1..MAX_HORIZON_DAYS) {
            "query parameter 'horizonDays' must be between 1 and $MAX_HORIZON_DAYS"
        }
        val book = load(runId, bookId, null)
        val to = book.context.run.asOf
        val from = to.minusDays(varSettings.windowDays.toLong())
        fun result(count: Int, skipped: Int, figures: com.openbank.risk.domain.valuation.VarFigures?, gap: String?) =
            VarAnalysis(
                book.context,
                confidence,
                horizonDays,
                varSettings.minimumScenarios,
                from,
                to,
                count,
                skipped,
                figures,
                gap,
            )
        if (book.gap != null) return result(0, 0, null, book.gap)

        val currencies = book.market.fxToReporting.keys
        val history = curveSets.latestPerDayBetween(from, to).map { set ->
            HistoricalMarket(set.asOf, set.curves, fixings.inEffect(currencies, set.asOf).mapValues { it.value.rate })
        }
        val basePv = book.valuator.bookPv(book.prepared, book.market)
        var skipped = 0
        val pnl = HistoricalVar.pairs(history, horizonDays).mapNotNull { (a, b) ->
            val shocked = HistoricalVar.shocked(book.market, a, b)
            if (shocked == null) skipped++
            shocked?.let { book.valuator.bookPv(book.prepared, it).subtract(basePv, PRICING_MC) }
        }
        if (pnl.size < varSettings.minimumScenarios) {
            return result(
                pnl.size,
                skipped,
                null,
                "${pnl.size} complete scenario(s) in the window $from..$to, below the configured minimum of " +
                    "${varSettings.minimumScenarios}: VaR is not stated over too little history",
            )
        }
        return result(pnl.size, skipped, HistoricalVar.figures(pnl, confidence), null)
    }

    override suspend fun plannedFlows(asOf: LocalDate, curveSet: CurveSet): List<CashFlow> =
        deals.valuationCandidates(asOf).filter { it.kind == DealKind.PLANNED && it.maturityDate.isAfter(asOf) }
            .flatMap { deal -> TreasuryLegs.legsOf(deal).orEmpty() }
            .flatMap { leg ->
                val forward = (leg.rate as? RateSpec.Floating)?.let { f ->
                    CurveIndex.forIndexName(f.index)?.let { curveSet.curves[it] }?.projection()
                        ?: return@flatMap emptyList()
                }
                CashFlowProjector.project(ScheduleGenerator.generate(leg), forward = forward).flows
                    .filter { it.paymentDate.isAfter(asOf) && it.amount.signum() != 0 }
                    .map { f ->
                        CashFlow(
                            f.paymentDate,
                            f.currency.code,
                            if (f.kind == CashFlowKind.INTEREST) {
                                com.openbank.risk.domain.cashflow.CashFlowKind.INTEREST
                            } else {
                                com.openbank.risk.domain.cashflow.CashFlowKind.PRINCIPAL
                            },
                            f.amount,
                        )
                    }
            }

    private class LoadedBook(
        val context: ValuationContext,
        val market: MarketState,
        val valuator: TreasuryValuator,
        val prepared: List<TreasuryValuator.Prepared>,
        val unpriced: List<DealValuation>,
        val gap: String?,
    )

    private suspend fun load(runId: UUID, bookId: String?, curveSetId: UUID?): LoadedBook {
        snapshots.getInstruments(runId) // 404 unknown, 409 UNTIED — the same gate as every snapshot read
        val run = snapshots.getRun(runId)
        val set = curveSet(run.asOf, curveSetId)
        val scoped = deals.valuationCandidates(run.asOf)
            .filter { it.kind == DealKind.ACTUAL && it.inValuationScope(run.asOf) }
            .filter { bookId == null || it.bookId == bookId }
            .sortedBy { it.dealId }
        val stored = storedFlows.latest(scoped.map { it.dealId })
        val valuable = scoped.mapNotNull { d ->
            val legs = TreasuryLegs.legsOf(d).orEmpty()
            val flows = stored[d.dealId]
            if (legs.isEmpty() && flows == null) null else ValuableDeal(d.dealId, d.bookId, d.kind, legs, flows)
        }
        val notValuable = scoped.filter { d -> valuable.none { it.dealId == d.dealId } }
        val currencies = valuable.flatMap { v ->
            v.legs.map { it.currency.code } +
                v.stored?.flows.orEmpty().map { it.currency }
        }.toSet()
        val fx = fixings.inEffect(currencies, run.asOf)
        val market = market(set, REPORTING, fx)
        val valuator = TreasuryValuator(market)
        val (prepared, unpriced) = prepareAll(valuator, valuable, notValuable)
        val gap = unpriced.takeIf { it.isNotEmpty() }?.let { u ->
            "${u.size} deal(s) cannot be valued (${u.take(LISTED).joinToString { "${it.dealId}: ${it.unpriced}" }}): " +
                "book figures are not stated over a partial book"
        }
        val provenance = if (run.provenance == Provenance.SYNTHETIC || set.provenance == Provenance.SYNTHETIC) {
            ReportProvenance.SYNTHETIC
        } else {
            ReportProvenance.REGULATORY
        }
        val context = ValuationContext(
            run = run,
            curveSetId = set.id,
            bookId = bookId,
            reportingCurrency = REPORTING,
            provenance = provenance,
            valuationInputHash = inputHash(run, set, bookId, fx, valuable, notValuable),
            fxRates = fx,
        )
        return LoadedBook(context, market, valuator, prepared, unpriced, gap)
    }

    private fun prepareAll(
        valuator: TreasuryValuator,
        valuable: List<ValuableDeal>,
        notValuable: List<TreasuryDeal>,
    ): Pair<List<TreasuryValuator.Prepared>, List<DealValuation>> {
        val prepared = mutableListOf<TreasuryValuator.Prepared>()
        val unpriced = mutableListOf<DealValuation>()
        valuable.forEach { v ->
            when (val p = valuator.prepare(v)) {
                is TreasuryValuator.Preparation.Ready -> prepared += p.prepared
                is TreasuryValuator.Preparation.Unpriced ->
                    unpriced +=
                        unpricedOf(v.dealId, v.bookId, v.stored != null, p.reason)
            }
        }
        notValuable.forEach {
            unpriced +=
                unpricedOf(
                    it.dealId,
                    it.bookId,
                    false,
                    "no legs published and none derivable for product ${it.product}",
                )
        }
        return prepared to unpriced
    }

    private fun unpricedOf(id: UUID, book: String?, stored: Boolean, reason: String) = DealValuation(
        id,
        book,
        DealKind.ACTUAL,
        if (stored) FlowSource.STORED else FlowSource.DERIVED,
        emptyList(),
        reason,
    )

    private suspend fun curveSet(asOf: LocalDate, curveSetId: UUID?): CurveSet {
        val id = curveSetId ?: requireNotNull(curveSets.latestIdFor(asOf)) {
            "no curve set recorded as of $asOf: upload one or pass 'curveSetId'"
        }
        val set = curveSets.findById(id) ?: throw CurveSetNotFoundException(id)
        require(set.asOf == asOf) { "curve set ${set.id} is as of ${set.asOf} but the valuation is as of $asOf" }
        return set
    }

    private fun market(set: CurveSet, reporting: String, fx: Map<String, FxRateUsed>): MarketState {
        // ReportingFixings quotes every currency in CZK; another reporting currency is the cross.
        val toCzk = fx.mapValues { it.value.rate } + (REPORTING to BigDecimal.ONE)
        val reportingInCzk = toCzk[reporting]
        val rates = if (reportingInCzk == null) {
            emptyMap()
        } else {
            toCzk.filterKeys { it != reporting }.mapValues { it.value.divide(reportingInCzk, PRICING_MC) }
        }
        return MarketState(set.asOf, set.curves, reporting, rates)
    }

    @Suppress("LongParameterList")
    private fun inputHash(
        run: SnapshotRun,
        set: CurveSet,
        bookId: String?,
        fx: Map<String, FxRateUsed>,
        valuable: List<ValuableDeal>,
        notValuable: List<TreasuryDeal>,
    ): String {
        val lines = mutableListOf(
            "run=${run.asOf}|${run.inputHash}",
            "curveSet=${set.id}",
            "models=${CurveBootstrap.VERSION}|${ValuationModel.VERSION}",
            "book=${bookId ?: "*"}",
        )
        fx.toSortedMap().forEach { (ccy, r) ->
            lines +=
                "fx=$ccy|${r.rate.stripTrailingZeros().toPlainString()}|${r.fixingDate}"
        }
        valuable.forEach { v ->
            val legs = v.legs.joinToString(",") { ScheduleHash.of(ScheduleGenerator.generate(it)) }
            lines +=
                "deal=${v.dealId}|${v.kind}|${v.bookId ?: "-"}|$legs|${v.stored?.let {
                    "${it.dealVersion}:${it.scheduleHash}"
                } ?: "-"}"
        }
        notValuable.forEach { lines += "unvaluable=${it.dealId}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val REPORTING = ReportingCurrencyTotal.REPORTING_CURRENCY
        val PRICING_DEAL_ID: UUID = UUID(0L, 0L)
        const val LISTED = 5
        const val MAX_HORIZON_DAYS = 10
    }
}
