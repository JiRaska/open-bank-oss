// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain.model

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.calendar.YearFraction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** How a product consumes its counterparty's credit line (ADR-0315 D4 extended to ADR-0344 D2). */
enum class CreditMeasure {
    /** A borrowing or a cash movement: the bank owes, the counterparty does not. */
    NONE,

    /** A placement, loan or security held: the principal. */
    PRINCIPAL,

    /** FX spot settlement risk: the CZK leg (principal × rate), until settlement. */
    FX_SPOT_CZK,

    /** A derivative: |notional| × the conversion factor of its product and residual maturity. */
    CONVERSION_FACTOR,

    /** Reverse repo: the cash lent less the haircut collateral value received, floored at zero. */
    REVERSE_REPO_GAP,

    /** Repo: the collateral value delivered less the cash received, floored at zero. */
    REPO_GAP,
    ;

    companion object {
        fun of(product: ProductType): CreditMeasure = when (product) {
            ProductType.FX_SPOT -> FX_SPOT_CZK
            ProductType.FX_FORWARD, ProductType.FX_SWAP, ProductType.FRA, ProductType.IRS -> CONVERSION_FACTOR
            ProductType.REVERSE_REPO -> REVERSE_REPO_GAP
            ProductType.REPO -> REPO_GAP
            else -> if (product.isAsset) PRINCIPAL else NONE
        }

        /** The products whose credit line is measured in CZK, whatever the deal currency. */
        val CZK_LINE_PRODUCTS = setOf(ProductType.FX_SPOT, ProductType.FX_FORWARD, ProductType.FX_SWAP)
    }
}

/**
 * `derivative-conversion-factor` reference record (generic payload: `product`, `maxResidualYears`
 * — null for the open-ended bucket — and `factorPercent`).
 */
data class ConversionFactor(val product: ProductType, val maxResidualYears: BigDecimal?, val factorPercent: BigDecimal)

/** `collateral-haircut` reference record (generic payload: `isin` — `*` for the default — and `haircutPercent`). */
data class CollateralHaircut(val isin: String, val haircutPercent: BigDecimal) {
    companion object {
        const val ANY = "*"
    }
}

/**
 * The bank's own price of a collateral security, percent of nominal (dirty), from an ACTIVE
 * four-eyes market-data observation keyed by the ISIN (ADR-0344 D5) — never the deal's own figure.
 */
data class CollateralPrice(val isin: String, val date: LocalDate, val dirtyPricePercent: BigDecimal)

/**
 * The credit-measurement reference data effective on a deal's trade date. [collateralPrice] is the
 * independent price of the deal's collateral, [priceTolerancePercent] how far a price the deal was
 * struck at may sit from it (`limit-settings` field `collateralPriceTolerancePercent`); null means no
 * record is effective, and then a deal price is accepted only when it equals the independent one.
 */
data class CreditTerms(
    val factors: List<ConversionFactor>,
    val haircuts: List<CollateralHaircut>,
    val collateralPrice: CollateralPrice? = null,
    val priceTolerancePercent: BigDecimal? = null,
) {
    companion object {
        val NONE = CreditTerms(emptyList(), emptyList())
    }
}

/**
 * A deal cannot be measured against its credit line because a reference record it needs is not
 * effective (422 CONVERSION_FACTOR_MISSING / COLLATERAL_HAIRCUT_MISSING / COLLATERAL_PRICE_MISSING), or
 * the price the deal states for its collateral is too far from the bank's own (422
 * COLLATERAL_PRICE_OUT_OF_TOLERANCE). Never a zero exposure.
 */
class CreditMeasureMissingException(val code: String, message: String) : RuntimeException(message)

/**
 * ADR-0315 D4: the ONE measure of what a deal adds to its counterparty's credit line, in the line's
 * currency ([limitCurrency]). The pre-trade check computes it; the deal stores it with its
 * [LimitCheck] (`limit_deal_amount`), and the locked line-exposure query sums exactly those stored
 * figures — so the two can never measure a deal differently.
 */
object CreditExposure {
    private const val PERCENT = 100L
    private const val YEARS_SCALE = 6
    private val HUNDRED = BigDecimal.valueOf(PERCENT)
    const val PRICE_MISSING = "COLLATERAL_PRICE_MISSING"
    const val PRICE_OUT_OF_TOLERANCE = "COLLATERAL_PRICE_OUT_OF_TOLERANCE"

    fun limitCurrency(product: ProductType, currency: String): String =
        if (product in CreditMeasure.CZK_LINE_PRODUCTS) Deal.CZK else currency

    fun of(deal: Deal, asOf: LocalDate, terms: CreditTerms): BigDecimal = when (CreditMeasure.of(deal.product)) {
        CreditMeasure.NONE -> BigDecimal.ZERO
        CreditMeasure.PRINCIPAL -> deal.principal
        CreditMeasure.FX_SPOT_CZK -> checkNotNull(deal.fx) { "an FX spot carries its CZK leg" }.counterAmount
        CreditMeasure.CONVERSION_FACTOR -> percentOf(notional(deal), factor(deal, asOf, terms))
        CreditMeasure.REVERSE_REPO_GAP -> (deal.principal - haircutCollateral(deal, terms)).max(BigDecimal.ZERO)
        CreditMeasure.REPO_GAP -> (collateralValue(deal, terms) - deal.principal).max(BigDecimal.ZERO)
    }.setScale(2, RoundingMode.HALF_UP)

    /** The notional the factor applies to, in the line currency: the CZK leg of an FX derivative. */
    private fun notional(deal: Deal): BigDecimal = if (deal.product in CreditMeasure.CZK_LINE_PRODUCTS) {
        deal.legs.filter { it.currency == Deal.CZK }.maxOfOrNull { it.notional.abs() }
            ?: throw IllegalArgumentException("an ${deal.product} deal needs a CZK leg to be measured on its CZK line")
    } else {
        deal.legs.filter { it.currency == deal.currency }.maxOfOrNull { it.notional.abs() } ?: deal.principal.abs()
    }

    private fun factor(deal: Deal, asOf: LocalDate, terms: CreditTerms): BigDecimal {
        // Residual maturity as an exact ACT/365F fraction from the kernel; the bucket bound is compared
        // as a rational (no rounded decimal), so a deal exactly on a boundary stays in the lower bucket.
        val residual = if (deal.maturityDate.isAfter(asOf)) {
            DayCount.ACT_365_FIXED.yearFraction(asOf, deal.maturityDate)
        } else {
            YearFraction.ZERO
        }
        val residualYears = residual.toBigDecimal().setScale(YEARS_SCALE, RoundingMode.HALF_UP)
        return terms.factors
            .filter {
                it.product == deal.product && (it.maxResidualYears == null || within(residual, it.maxResidualYears))
            }
            .minByOrNull { it.maxResidualYears ?: BigDecimal(Long.MAX_VALUE) }
            ?.factorPercent
            ?: throw CreditMeasureMissingException(
                "CONVERSION_FACTOR_MISSING",
                "no derivative-conversion-factor for ${deal.product} at $residualYears years residual is effective on $asOf",
            )
    }

    private fun within(residual: YearFraction, maxYears: BigDecimal): Boolean =
        BigDecimal(residual.numerator) <= maxYears.multiply(BigDecimal(residual.denominator))

    /**
     * Collateral at the bank's own dirty price ([CreditTerms.collateralPrice]). A repo without
     * collateral, or a collateral the bank has no price for, is refused — valued at zero it would
     * make a repo consume nothing. The price the deal was struck at is only checked against the
     * bank's: a dealer cannot raise the cover (and shrink the exposure) by stating a higher one.
     */
    private fun collateralValue(deal: Deal, terms: CreditTerms): BigDecimal {
        val c = deal.header.collateral ?: refuse(
            PRICE_MISSING,
            "a ${deal.product} deal names no collateral, so its credit exposure cannot be measured",
        )
        val independent = terms.collateralPrice?.takeIf { it.isin == c.securityIsin }
            ?: refuse(
                PRICE_MISSING,
                "no ACTIVE market-data price for collateral ${c.securityIsin} is effective on ${deal.tradeDate}",
            )
        val price = independent.dirtyPricePercent
        c.dirtyPrice?.let { stated ->
            val tolerance = terms.priceTolerancePercent ?: BigDecimal.ZERO
            val deviationPercent =
                (stated - price).abs().multiply(HUNDRED).divide(price, YEARS_SCALE, RoundingMode.HALF_UP)
            if (deviationPercent > tolerance) {
                refuse(
                    PRICE_OUT_OF_TOLERANCE,
                    "collateral ${c.securityIsin} is stated at $stated, $deviationPercent % from the bank's price " +
                        "$price of ${independent.date} (tolerance $tolerance %)",
                )
            }
        }
        return percentOf(c.nominal, price)
    }

    private fun haircutCollateral(deal: Deal, terms: CreditTerms): BigDecimal {
        val c = deal.header.collateral ?: throw CreditMeasureMissingException(
            PRICE_MISSING,
            "a ${deal.product} deal names no collateral, so its credit exposure cannot be measured",
        )
        val reference = (
            terms.haircuts.firstOrNull { it.isin == c.securityIsin }
                ?: terms.haircuts.firstOrNull { it.isin == CollateralHaircut.ANY }
            )?.haircutPercent
            ?: throw CreditMeasureMissingException(
                "COLLATERAL_HAIRCUT_MISSING",
                "no collateral-haircut for ${c.securityIsin} (or the default) is effective",
            )
        // The stricter of the bank's haircut and the one the deal was struck with.
        val haircut = reference.max(c.haircutPercent)
        return percentOf(collateralValue(deal, terms), HUNDRED - haircut)
    }

    private fun refuse(code: String, message: String): Nothing = throw CreditMeasureMissingException(code, message)

    private fun percentOf(amount: BigDecimal, percent: BigDecimal): BigDecimal =
        amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP)
}
