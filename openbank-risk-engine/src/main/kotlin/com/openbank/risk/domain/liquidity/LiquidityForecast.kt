// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.domain.cashflow.CashFlow
import com.openbank.risk.domain.cashflow.SourcedFlows
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One row of the ladder: calendar days [fromDay]..[toDay] after as-of (inclusive), with inflows
 * (positive) and outflows (negative) split by source, and the cumulative position at the END of
 * the row — opening liquidity plus every net flow up to and including [toDay].
 */
data class ForecastRow(
    val fromDay: Int,
    val toDay: Int,
    val from: LocalDate,
    val to: LocalDate,
    val contractualInflows: BigDecimal,
    val contractualOutflows: BigDecimal,
    val behaviouralInflows: BigDecimal,
    val behaviouralOutflows: BigDecimal,
    val cumulative: BigDecimal,
) {
    val inflows: BigDecimal get() = contractualInflows.add(behaviouralInflows)
    val outflows: BigDecimal get() = contractualOutflows.add(behaviouralOutflows)
    val net: BigDecimal get() = inflows.add(outflows)
}

/**
 * The forecast of one currency. [opening] is the HQLA stock after haircuts and caps exactly as the
 * LCR computes it ([hqla]; null when the currency holds no HQLA line, and then [opening] is zero).
 * [survivalDay] is the first calendar day on which the cumulative position is negative, or null
 * when it stays non-negative for the whole horizon.
 */
data class CurrencyForecast(
    val currency: String,
    val hqla: HqlaStock?,
    val opening: BigDecimal,
    val rows: List<ForecastRow>,
    val survivalDay: Int?,
    val survivalDate: LocalDate?,
    val minimumCumulative: BigDecimal,
    val flowsBeyondHorizon: Int,
)

data class ForecastAssumption(val key: String, val statement: String)

data class LiquidityForecastResult(
    val horizonDays: Int,
    val dailyDays: Int,
    val currencies: List<CurrencyForecast>,
    val assumptions: List<ForecastAssumption>,
)

/**
 * Liquidity survival / funding-gap forecast of a tied-out snapshot (ADR-0313 "forecasting").
 *
 * No model of its own: the flows are the ones [com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection]
 * derives for the cash-flow and IRRBB reads, and the opening position is the HQLA stock
 * [Liquidity.compute] reports for the LCR — so the forecast cannot disagree with either. What
 * neither of them models is listed in [ASSUMPTIONS] as not modelled, never filled in here.
 *
 * Ladder: one row per calendar day for the first [DAILY_DAYS] days (the LCR horizon), then weekly
 * rows, the last one cut at the horizon. The survival day is found on the DAILY cumulative, so a
 * weekly row never hides the day the position first turns negative.
 */
object LiquidityForecast {

    const val MIN_HORIZON_DAYS = 1
    const val MAX_HORIZON_DAYS = 365
    const val DEFAULT_HORIZON_DAYS = 90
    const val DAILY_DAYS = 30
    private const val DAYS_PER_WEEK = 7

    val ASSUMPTIONS = listOf(
        ForecastAssumption(
            "opening-liquidity",
            "Opening liquidity is the HQLA stock after haircuts and the Level 2 caps, exactly as the LCR " +
                "reports it under the same liquidity parameter set; it is treated as monetisable on day 0.",
        ),
        ForecastAssumption(
            "same-projection",
            "Flows are the snapshot's cash-flow projection unchanged: loans contractual (FIXED from lending's " +
                "schedule, FLOATING from the curve set), customer deposits behavioural under the named model.",
        ),
        ForecastAssumption(
            "past-due-day-1",
            "A flow dated on or before the as-of date counts on day 1, the same convention as the overnight " +
                "bucket of the cash-flow ladder.",
        ),
        ForecastAssumption(
            "gl-positions-not-modelled",
            "GL-level positions other than HQLA (nostro, money-market placements, borrowings, capital) carry " +
                "no contract terms in the snapshot and project no flows: not modelled.",
        ),
        ForecastAssumption(
            "new-business-not-modelled",
            "New business is not modelled: no new loans, no new deposits, no rollover of maturing loans.",
        ),
        ForecastAssumption(
            "stress-not-modelled",
            "No stress scenario is applied beyond the behavioural model's volatile share; this is a " +
                "business-as-usual projection, not the LCR's 30-day stress.",
        ),
        ForecastAssumption(
            "calendar-days",
            "Days are calendar days after as-of; there is no holiday calendar.",
        ),
        ForecastAssumption(
            "per-currency",
            "Computed per currency; no FX conversion, so a surplus in one currency never covers a gap in another.",
        ),
    )

    fun forecast(
        flows: Map<String, SourcedFlows>,
        hqla: Map<String, HqlaStock>,
        asOf: LocalDate,
        horizonDays: Int,
    ): LiquidityForecastResult {
        require(horizonDays in MIN_HORIZON_DAYS..MAX_HORIZON_DAYS) {
            "horizonDays must be between $MIN_HORIZON_DAYS and $MAX_HORIZON_DAYS: $horizonDays"
        }
        val currencies = (flows.keys + hqla.keys).sorted().map { ccy ->
            currency(ccy, flows[ccy] ?: SourcedFlows(emptyList(), emptyList()), hqla[ccy], asOf, horizonDays)
        }
        return LiquidityForecastResult(horizonDays, minOf(DAILY_DAYS, horizonDays), currencies, ASSUMPTIONS)
    }

    private class Day {
        var contractualIn: BigDecimal = BigDecimal.ZERO
        var contractualOut: BigDecimal = BigDecimal.ZERO
        var behaviouralIn: BigDecimal = BigDecimal.ZERO
        var behaviouralOut: BigDecimal = BigDecimal.ZERO
        val net: BigDecimal get() = contractualIn.add(contractualOut).add(behaviouralIn).add(behaviouralOut)
    }

    private fun currency(
        ccy: String,
        sourced: SourcedFlows,
        stock: HqlaStock?,
        asOf: LocalDate,
        horizon: Int,
    ): CurrencyForecast {
        val days = Array(horizon + 1) { Day() } // index 0 unused: day 1 is the first day after as-of
        var beyond = 0
        fun add(f: CashFlow, contractual: Boolean) {
            val day = maxOf(1L, ChronoUnit.DAYS.between(asOf, f.date))
            if (day > horizon) {
                beyond++
                return
            }
            val d = days[day.toInt()]
            val inflow = f.amount.signum() > 0
            when {
                contractual && inflow -> d.contractualIn = d.contractualIn.add(f.amount)
                contractual -> d.contractualOut = d.contractualOut.add(f.amount)
                inflow -> d.behaviouralIn = d.behaviouralIn.add(f.amount)
                else -> d.behaviouralOut = d.behaviouralOut.add(f.amount)
            }
        }
        sourced.contractual.forEach { add(it, contractual = true) }
        sourced.behavioural.forEach { add(it, contractual = false) }

        val opening = stock?.stock ?: BigDecimal.ZERO
        val cumulative = Array(horizon + 1) { BigDecimal.ZERO }
        cumulative[0] = opening
        for (d in 1..horizon) cumulative[d] = cumulative[d - 1].add(days[d].net)
        val survival = (1..horizon).firstOrNull { cumulative[it].signum() < 0 }

        val rows = rowBounds(horizon).map { (from, to) ->
            val span = (from..to).map { days[it] }
            ForecastRow(
                fromDay = from,
                toDay = to,
                from = asOf.plusDays(from.toLong()),
                to = asOf.plusDays(to.toLong()),
                contractualInflows = span.sumOf { it.contractualIn },
                contractualOutflows = span.sumOf { it.contractualOut },
                behaviouralInflows = span.sumOf { it.behaviouralIn },
                behaviouralOutflows = span.sumOf { it.behaviouralOut },
                cumulative = cumulative[to],
            )
        }
        return CurrencyForecast(
            currency = ccy,
            hqla = stock,
            opening = opening,
            rows = rows,
            survivalDay = survival,
            survivalDate = survival?.let { asOf.plusDays(it.toLong()) },
            minimumCumulative = (0..horizon).minOf { cumulative[it] },
            flowsBeyondHorizon = beyond,
        )
    }

    /** Daily rows up to [DAILY_DAYS], then weekly rows; together they cover 1..[horizon] exactly once. */
    internal fun rowBounds(horizon: Int): List<Pair<Int, Int>> {
        val daily = (1..minOf(DAILY_DAYS, horizon)).map { it to it }
        val weekly = (DAILY_DAYS + 1..horizon step DAYS_PER_WEEK).map { it to minOf(it + DAYS_PER_WEEK - 1, horizon) }
        return daily + weekly
    }
}
