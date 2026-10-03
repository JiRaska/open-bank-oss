// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.exp

/** One pillar as the risk engine serves it: a continuously-compounded zero rate (decimal), ACT/365F. */
data class CurvePillar(val date: LocalDate, val zeroRate: BigDecimal)

data class MarketCurve(val index: String, val currency: String, val pillars: List<CurvePillar>)

/** The risk engine's curve set (ADR-0313 D4), reduced to what a quote reads. */
data class CurveSetView(
    val id: UUID,
    val asOf: LocalDate,
    /** The risk engine's own label for the market data: `synthetic` or `production`. */
    val provenance: String,
    val curves: List<MarketCurve>,
)

/**
 * A SIMULATED counterparty's two-way money-market quote (ADR-0315 D9): the curve mid for the tenor
 * plus/minus the counterparty's configured half-spread. Always [synthetic] — an invented
 * counterparty quoting off a curve, never a price anyone dealt at.
 *
 * Side convention, from the COUNTERPARTY's book: [bid] is what it pays to take a deposit (the bank
 * PLACES at the bid), [ask] what it charges to lend (the bank BORROWS at the ask). Rates are annual
 * percentages, ACT/360 simple, like [Deal.rate].
 */
data class SimulatedQuote(
    val counterpartyId: String,
    val currency: String,
    val tenorDays: Int,
    val curveSetId: UUID,
    val curveAsOf: LocalDate,
    val curveIndex: String,
    val curveProvenance: String,
    val mid: BigDecimal,
    val spreadBp: Int,
    val bid: BigDecimal,
    val ask: BigDecimal,
) {
    val synthetic: Boolean get() = true

    /** The rate a deal of [product] is struck at against this quote: the bid for a placement, the ask for a borrowing. */
    fun rateFor(product: ProductType): BigDecimal = when (product) {
        ProductType.MM_PLACEMENT -> bid
        ProductType.MM_BORROWING -> ask
        else -> throw IllegalArgumentException("only MM_PLACEMENT and MM_BORROWING are quoted")
    }

    /**
     * Whether the simulated counterparty confirms [deal] (ADR-0315 D9): the deal was struck at the
     * quote or better for the counterparty — a placement at no more than its bid, a borrowing at no
     * less than its ask. A dealer who typed an off-market rate gets no synthetic confirmation.
     */
    fun accepts(deal: Deal): Boolean = when (deal.product) {
        ProductType.MM_PLACEMENT -> deal.rate <= bid
        ProductType.MM_BORROWING -> deal.rate >= ask
        else -> throw IllegalArgumentException("only MM_PLACEMENT and MM_BORROWING are quoted")
    }
}

/**
 * Prices a simulated quote off a curve set. The curve is the currency's overnight RFR curve — the
 * one the risk engine discounts with (CZK → CZEONIA, EUR → ESTR) — interpolated the risk engine's
 * way: linear on zero rates between pillars, flat outside them. The zero rate for the tenor is
 * turned into the money-market convention the deals use (ACT/360 simple, percent):
 * `(1 / DF - 1) × 360 / days × 100`, with `DF = exp(-z × days / 365)`.
 */
object QuotePricer {
    const val MIN_TENOR_DAYS = 1
    const val MAX_TENOR_DAYS = 365
    val QUOTED_PRODUCTS: Set<ProductType> = setOf(ProductType.MM_PLACEMENT, ProductType.MM_BORROWING)

    private val CURVE_BY_CURRENCY = mapOf(Deal.CZK to "CZEONIA", Deal.EUR to "ESTR")
    private const val DAYS_ACT360 = 360.0
    private const val DAYS_ACT365 = 365.0
    private const val PERCENT = 100.0
    private val BP_TO_PERCENT = BigDecimal("0.01")

    fun curveIndexFor(currency: String): String =
        requireNotNull(CURVE_BY_CURRENCY[currency]) { "no quote curve for currency $currency" }

    /**
     * The curve mid for [tenorDays] from the curve set's as-of date, ACT/360 simple percent, 4 dp.
     * A tenor past the last pillar reads the last zero rate (flat), as the risk engine does; the
     * endpoint caps a REQUESTED tenor at [MAX_TENOR_DAYS], a booked deal's own tenor is priced as is.
     */
    fun midRate(curve: MarketCurve, asOf: LocalDate, tenorDays: Int): BigDecimal {
        require(tenorDays >= MIN_TENOR_DAYS) { "tenorDays must be at least $MIN_TENOR_DAYS" }
        require(curve.pillars.isNotEmpty()) { "curve ${curve.index} has no pillars" }
        val z = zeroAt(curve.pillars.sortedBy { it.date }, asOf.plusDays(tenorDays.toLong()))
        val df = exp(-z * tenorDays / DAYS_ACT365)
        val simple = (1.0 / df - 1.0) * DAYS_ACT360 / tenorDays * PERCENT
        return RoundingPolicy.RATE_PERCENT.round(BigDecimal.valueOf(simple))
    }

    /**
     * [counterpartyId]'s quote: the mid ± [spreadBp] basis points. The bid is floored at zero
     * because a deal rate may not be negative ([Deal] init); the ask is never below the bid.
     */
    fun quote(
        set: CurveSetView,
        counterpartyId: String,
        currency: String,
        tenorDays: Int,
        spreadBp: Int,
    ): SimulatedQuote {
        require(spreadBp >= 0) { "a spread must not be negative" }
        val index = curveIndexFor(currency)
        val curve = set.curves.firstOrNull { it.index == index }
            ?: throw QuoteUnavailableException("curve set ${set.id} (${set.asOf}) holds no $index curve")
        val mid = midRate(curve, set.asOf, tenorDays)
        val half = BP_TO_PERCENT.multiply(BigDecimal(spreadBp))
        return SimulatedQuote(
            counterpartyId = counterpartyId,
            currency = currency,
            tenorDays = tenorDays,
            curveSetId = set.id,
            curveAsOf = set.asOf,
            curveIndex = index,
            curveProvenance = set.provenance,
            mid = mid,
            spreadBp = spreadBp,
            bid = RoundingPolicy.RATE_PERCENT.round((mid - half).max(BigDecimal.ZERO)),
            ask = RoundingPolicy.RATE_PERCENT.round(mid + half),
        )
    }

    private fun zeroAt(pillars: List<CurvePillar>, date: LocalDate): Double {
        val first = pillars.first()
        val last = pillars.last()
        return when {
            !date.isAfter(first.date) -> first.zeroRate.toDouble()
            !date.isBefore(last.date) -> last.zeroRate.toDouble()
            else -> {
                val right = pillars.first { !it.date.isBefore(date) }
                val left = pillars.last { !it.date.isAfter(date) }
                if (left.date == right.date) {
                    left.zeroRate.toDouble()
                } else {
                    val span = ChronoUnit.DAYS.between(left.date, right.date).toDouble()
                    val w = ChronoUnit.DAYS.between(left.date, date) / span
                    left.zeroRate.toDouble() + w * (right.zeroRate.toDouble() - left.zeroRate.toDouble())
                }
            }
        }
    }
}

/** No quote can be priced: no curve set, no curve for the currency, or the risk engine unreachable. Mapped to 503. */
class QuoteUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
