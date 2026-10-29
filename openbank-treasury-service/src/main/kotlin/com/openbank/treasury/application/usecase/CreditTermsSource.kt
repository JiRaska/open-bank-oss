// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.treasury.application.port.out.MarketDataRepository
import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.domain.model.CollateralHaircut
import com.openbank.treasury.domain.model.CollateralPrice
import com.openbank.treasury.domain.model.ConversionFactor
import com.openbank.treasury.domain.model.CreditTerms
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.GenericPayload
import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceStatus
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ADR-0315 D4: the `derivative-conversion-factor` and `collateral-haircut` records ACTIVE and
 * effective on [on]. A record whose fields do not parse is skipped, so it can only make a measure
 * MISSING (a refusal), never a smaller one.
 *
 * The collateral of a repo is priced by the bank, not by the deal: the latest ACTIVE (four-eyes)
 * market-data observation keyed by the collateral ISIN, dated on [on] or at most
 * `collateralPriceMaxAgeDays` (a `limit-settings` field, 0 when no record states it) before it. How
 * far a deal's own price may sit from it is the `collateralPriceTolerancePercent` field of the same
 * record; with none effective, only an equal price is accepted.
 */
class CreditTermsSource(private val references: ReferenceRepository, private val marketData: MarketDataRepository) {
    suspend fun on(on: LocalDate, collateralIsin: String?): CreditTerms = CreditTerms(
        factors = fields(ReferenceKind.DERIVATIVE_CONVERSION_FACTOR, on).mapNotNull { f ->
            runCatching {
                ConversionFactor(
                    product = ProductType.valueOf(f.getValue("product").toString()),
                    maxResidualYears = f["maxResidualYears"]?.let { BigDecimal(it.toString()) },
                    factorPercent = BigDecimal(f.getValue("factorPercent").toString()).also {
                        require(it.signum() > 0) { "a conversion factor is positive" }
                    },
                )
            }.getOrNull()
        },
        haircuts = fields(ReferenceKind.COLLATERAL_HAIRCUT, on).mapNotNull { f ->
            runCatching {
                CollateralHaircut(
                    isin = f.getValue("isin").toString(),
                    haircutPercent = BigDecimal(f.getValue("haircutPercent").toString()).also {
                        require(it.signum() >= 0 && it <= HUNDRED) { "a haircut is 0..100" }
                    },
                )
            }.getOrNull()
        },
        collateralPrice = collateralIsin?.let { price(it, on) },
        priceTolerancePercent = setting(on, "collateralPriceTolerancePercent")?.also {
            require(it.signum() >= 0) { "a price tolerance is not negative" }
        },
    )

    private suspend fun price(isin: String, on: LocalDate): CollateralPrice? {
        val maxAge = setting(on, "collateralPriceMaxAgeDays")?.toLong()?.coerceAtLeast(0) ?: 0
        return marketData.active(isin, on.minusDays(maxAge), on)
            .filter { it.value.signum() > 0 }
            .maxByOrNull { it.date }
            ?.let { CollateralPrice(isin, it.date, it.value) }
    }

    /** The smallest value of [field] over the effective `limit-settings` records (the stricter one). */
    private suspend fun setting(on: LocalDate, field: String): BigDecimal? = fields(ReferenceKind.LIMIT_SETTINGS, on)
        .mapNotNull { f -> f[field]?.let { runCatching { BigDecimal(it.toString()) }.getOrNull() } }
        .minOrNull()

    private suspend fun fields(kind: ReferenceKind, on: LocalDate): List<Map<String, Any?>> =
        references.list(kind, ReferenceStatus.ACTIVE, on).mapNotNull { (it.payload as? GenericPayload)?.fields }

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}
