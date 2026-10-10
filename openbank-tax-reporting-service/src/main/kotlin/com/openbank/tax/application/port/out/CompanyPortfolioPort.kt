// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.application.port.out

import java.math.BigDecimal
import java.time.LocalDate

/** One holding of the pension company's own investment portfolio at a period end. */
data class CompanyPortfolioPosition(
    val instrumentClass: String,
    val isin: String,
    val quantity: BigDecimal,
    val valuation: BigDecimal,
    val valuationCurrency: String,
)

/** The pension company's own portfolio (not a fund's) as its treasury reports it for [asOf]. */
data class CompanyPortfolio(val asOf: LocalDate, val currency: String, val positions: List<CompanyPortfolioPosition>)

/**
 * The pension company's own investment portfolio at a period end (ČNB PSP 34-12 PS, ADR-0337).
 * Sourced from the pension company's treasury instance, never the bank's.
 */
fun interface CompanyPortfolioPort {
    /** @throws ReturnDataUnavailableException when the source is not configured, refuses or answers malformed data. */
    suspend fun portfolio(periodEnd: LocalDate): CompanyPortfolio
}
