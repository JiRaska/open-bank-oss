// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.treasury.application.port.out.AnalysisFlow
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.MarketDataRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.reference.BookPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class FlowExpression { GROSS, NET, CUMULATIVE }

enum class FlowScenario { CONTRACTUAL, BEHAVIOURAL, STRESS }

/** A BEHAVIOURAL or STRESS analysis was asked for; no risk-engine scenario endpoint exists yet (422). */
class ScenarioNotAvailableException(val scenario: FlowScenario) :
    RuntimeException(
        "the $scenario scenario is not available: no behavioural or stress cash-flow model is published yet",
    )

/** Contract 8.4: the parameters of one cash-flow analysis. */
data class CashFlowAnalysisRequest(
    val calculationDate: LocalDate,
    val referenceCurrency: String,
    val convertToReference: Boolean = false,
    val expression: FlowExpression = FlowExpression.NET,
    val scenario: FlowScenario = FlowScenario.CONTRACTUAL,
    val splitMonthly: Boolean = false,
    val bookId: String? = null,
    val bankAccountId: String? = null,
    val includePlanned: Boolean = false,
    val horizonDays: Int = DEFAULT_HORIZON_DAYS,
) {
    init {
        require(CURRENCY.matches(referenceCurrency)) { "referenceCurrency must be an ISO 4217 code" }
        require(horizonDays in 1..MAX_HORIZON_DAYS) { "horizonDays must be 1..$MAX_HORIZON_DAYS" }
        require(bookId == null || bookId.length <= MAX_ID) { "bookId is at most $MAX_ID characters" }
        require(bankAccountId == null || bankAccountId.length <= MAX_ID) {
            "bankAccountId is at most $MAX_ID characters"
        }
    }

    companion object {
        const val DEFAULT_HORIZON_DAYS = 366
        const val MAX_HORIZON_DAYS = 3_660
        private const val MAX_ID = 64
        private val CURRENCY = Regex("^[A-Z]{3}$")
    }
}

/** One bucket (a day, or a month when split monthly): signed inflow / outflow, net, running total. */
data class FlowBucket(
    val date: LocalDate,
    val inflow: BigDecimal,
    val outflow: BigDecimal,
    val net: BigDecimal,
    val cumulative: BigDecimal,
    /** The figure [CashFlowAnalysisRequest.expression] asks for: GROSS → inflow + |outflow|, NET → net, CUMULATIVE → cumulative. */
    val value: BigDecimal,
)

data class FlowSeries(val currency: String, val buckets: List<FlowBucket>)

/** A drill-down row: one flow, signed (+ in, − out), with its reference-currency amount when converted. */
data class AnalysedFlow(
    val date: LocalDate,
    val currency: String,
    val amount: BigDecimal,
    val referenceAmount: BigDecimal?,
    val dealId: UUID,
    val legIndex: Int,
    val flowId: UUID,
    val kind: String,
    val dealKind: DealKind,
    val state: String,
)

data class FxUsed(val currency: String, val index: String, val rate: BigDecimal, val date: LocalDate)

data class AnalysisGap(val currency: String?, val reason: String)

data class CashFlowAnalysis(
    val request: CashFlowAnalysisRequest,
    val to: LocalDate,
    val series: List<FlowSeries>,
    /** The reference-currency series; null when not converting or when any currency lacks a fixing. */
    val reference: FlowSeries?,
    val flows: List<AnalysedFlow>,
    val flowsTruncated: Boolean,
    val fxRates: List<FxUsed>,
    val dataGaps: List<AnalysisGap>,
)

/**
 * Contract 8.4 cash-flow analysis, CONTRACTUAL only: the stored cash-flow ledger of live ACTUAL deals
 * (and PLANNED when asked), paying from the calculation date over a bounded horizon. Conversion to
 * the reference currency uses the four-eyes approved FX fixing of the calculation date (index
 * `FX_<CCY>_<REF>`: units of the reference currency per one unit of CCY); a currency without one is
 * a data gap, never a guessed rate, and leaves the reference series null. A flow without an amount
 * (an unfixed rate with no projection) is a gap too, never a zero. The book filter takes the book and
 * every descendant in the ACTIVE book hierarchy; the bank-account filter the deal's cash account.
 */
class CashFlowAnalysisService(
    private val cashFlows: CashFlowRepository,
    private val marketData: MarketDataRepository,
    private val references: ReferenceRepository,
) {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun analyse(r: CashFlowAnalysisRequest): CashFlowAnalysis {
        if (r.scenario != FlowScenario.CONTRACTUAL) throw ScenarioNotAvailableException(r.scenario)
        val to = r.calculationDate.plusDays(r.horizonDays.toLong())
        val kinds = if (r.includePlanned) setOf(DealKind.ACTUAL, DealKind.PLANNED) else setOf(DealKind.ACTUAL)
        val books = r.bookId?.let { books(it, r.calculationDate) }
        val gaps = mutableListOf<AnalysisGap>()
        val selected = cashFlows.analysisFlows(r.calculationDate, to, kinds, MAX_FLOWS).filter { f ->
            (books == null || f.bookId in books) && (r.bankAccountId == null || f.cashAccountId == r.bankAccountId)
        }
        val (priced, unpriced) = selected.partition { it.flow.amount != null }
        unpriced.groupBy { it.flow.currency }.forEach { (ccy, list) ->
            gaps += AnalysisGap(ccy, "${list.size} flow(s) have no amount yet (rate not fixed or projected)")
        }
        val fx = mutableMapOf<String, FxUsed>()
        if (r.convertToReference) {
            priced.map { it.flow.currency }.distinct().filter { it != r.referenceCurrency }.sorted().forEach { ccy ->
                val index = "FX_${ccy}_${r.referenceCurrency}"
                val rate = marketData.active(index, r.calculationDate, r.calculationDate)
                    .firstOrNull { it.date == r.calculationDate }?.value
                if (rate == null) {
                    gaps += AnalysisGap(ccy, "no approved $index fixing on ${r.calculationDate}")
                } else {
                    fx[ccy] = FxUsed(ccy, index, rate, r.calculationDate)
                }
            }
        }
        val signed = priced.map {
            it to signed(it)
        }.sortedWith(compareBy({ it.first.flow.paymentDate }, { it.first.flow.flowId }))
        val series = signed.groupBy { it.first.flow.currency }.toSortedMap()
            .map { (ccy, rows) -> FlowSeries(ccy, buckets(rows.map { it.first.flow.paymentDate to it.second }, r)) }
        val missingFx = r.convertToReference &&
            priced.any { it.flow.currency != r.referenceCurrency && it.flow.currency !in fx }
        val reference = if (!r.convertToReference || missingFx) {
            null
        } else {
            FlowSeries(
                r.referenceCurrency,
                buckets(signed.map { (f, amt) -> f.flow.paymentDate to convert(f.flow.currency, amt, r, fx)!! }, r),
            )
        }
        val rows = signed.take(MAX_ROWS).map { (f, amt) ->
            AnalysedFlow(
                f.flow.paymentDate,
                f.flow.currency,
                amt,
                if (r.convertToReference) convert(f.flow.currency, amt, r, fx) else null,
                f.flow.dealId,
                f.flow.legIndex,
                f.flow.flowId,
                f.flow.kind.name,
                f.kind,
                f.flow.state.name,
            )
        }
        return CashFlowAnalysis(r, to, series, reference, rows, signed.size > MAX_ROWS, fx.values.toList(), gaps)
    }

    private fun signed(f: AnalysisFlow): BigDecimal {
        val a = checkNotNull(f.flow.amount).abs()
        return if (f.flow.direction == FlowDirection.IN) a else a.negate()
    }

    private fun convert(
        ccy: String,
        amount: BigDecimal,
        r: CashFlowAnalysisRequest,
        fx: Map<String, FxUsed>,
    ): BigDecimal? = if (ccy ==
        r.referenceCurrency
    ) {
        amount
    } else {
        fx[ccy]?.let { RoundingPolicy.TREASURY_AMOUNT.round(amount * it.rate) }
    }

    private fun buckets(rows: List<Pair<LocalDate, BigDecimal>>, r: CashFlowAnalysisRequest): List<FlowBucket> {
        var running = BigDecimal.ZERO
        return rows.groupBy { (d, _) ->
            if (r.splitMonthly) d.withDayOfMonth(1) else d
        }.toSortedMap().map { (d, list) ->
            val inflow = list.map { it.second }.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
            val outflow = list.map { it.second }.filter { it.signum() < 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
            val net = inflow + outflow
            running += net
            val value = when (r.expression) {
                FlowExpression.GROSS -> inflow - outflow
                FlowExpression.NET -> net
                FlowExpression.CUMULATIVE -> running
            }
            FlowBucket(d, inflow, outflow, net, running, value)
        }
    }

    /** [root] and every book whose ACTIVE parent chain reaches it on [on]. */
    private suspend fun books(root: String, on: LocalDate): Set<String> {
        val parents = references.list(ReferenceKind.BOOK, ReferenceStatus.ACTIVE, on)
            .mapNotNull { it.payload as? BookPayload }
            .associate { it.id to it.parentId }
        val result = mutableSetOf(root)
        var grew = true
        while (grew) {
            val add = parents.filter { (id, parent) -> parent in result && id !in result }.keys
            grew = result.addAll(add)
        }
        return result
    }

    private companion object {
        const val MAX_FLOWS = 50_000
        const val MAX_ROWS = 2_000
    }
}
