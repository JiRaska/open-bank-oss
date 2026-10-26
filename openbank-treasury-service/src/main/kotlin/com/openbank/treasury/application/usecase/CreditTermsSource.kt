// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.treasury.application.port.out.ReferenceRepository
import com.openbank.treasury.domain.model.CollateralHaircut
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
 */
class CreditTermsSource(private val references: ReferenceRepository) {
    suspend fun on(on: LocalDate): CreditTerms = CreditTerms(
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
    )

    private suspend fun fields(kind: ReferenceKind, on: LocalDate): List<Map<String, Any?>> =
        references.list(kind, ReferenceStatus.ACTIVE, on).mapNotNull { (it.payload as? GenericPayload)?.fields }

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}
