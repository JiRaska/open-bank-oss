// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Meaning of a treasury position's quantity; values from different bases cannot be summed. */
enum class PortfolioQuantityBasis {
    UNIT,
    FACE_AMOUNT,
    AMORTISED_VALUE,
}

/** One holding of the pension company's own investment portfolio at a period end. */
data class CompanyPortfolioPosition(
    val instrumentClass: String,
    val isin: String,
    val quantity: BigDecimal,
    val valuation: BigDecimal,
    val valuationCurrency: String,
    val quantityBasis: PortfolioQuantityBasis,
)

/** Identifies one period-end custodian statement returned by treasury for a safekeeping account. */
data class CompanyPortfolioSourceStatement(
    val safekeepingAccount: String,
    val statementUuid: UUID,
    val statementId: String?,
    val version: Int,
)

/** The pension company's own portfolio (not a fund's) as its treasury reports it for [asOf]. */
data class CompanyPortfolio(
    val asOf: LocalDate,
    val currency: String,
    val positions: List<CompanyPortfolioPosition>,
    /** Provider provenance carried in memory; the statutory-return record currently stores datapoints only. */
    val sourceStatements: List<CompanyPortfolioSourceStatement> = emptyList(),
)

/**
 * The pension company's own investment portfolio at a period end (ČNB PSP 34-12 PS, ADR-0337).
 * Sourced from the pension company's treasury instance, never the bank's.
 */
fun interface CompanyPortfolioPort {
    /** @throws ReturnDataUnavailableException when the source is not configured, refuses or answers malformed data. */
    suspend fun portfolio(periodEnd: LocalDate): CompanyPortfolio
}
