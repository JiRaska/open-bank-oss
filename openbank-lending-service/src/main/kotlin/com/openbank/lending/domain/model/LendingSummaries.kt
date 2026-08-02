// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Backoffice aggregates (issue #3294).
 *
 * WHY THESE EXIST
 * `findRecent` / `findActive` are capped lists (server clamps to 1..100), so the console could only
 * ever report "of the newest 100" — and every figure a credit desk manages by is a total: how many
 * applications wait in four-eyes, how much exposure is requested, how big the book is. Rendering a
 * capped count as a total is a staffing decision made on a wrong number, so the console printed the
 * cap instead. These types are what let it stop doing that.
 *
 * MONEY IS PER CURRENCY, NEVER ONE NUMBER
 * `loan_application.currency` and `loan.currency` are per row. A single `sumRequestedAmount` would
 * be CZK added to EUR — a figure that looks authoritative and means nothing. Totals are therefore a
 * LIST keyed by currency, and a caller that wants one number has to say which currency it means.
 */
data class MoneyTotal(val currency: String, val amount: BigDecimal)

/** One origination state: how many sit there, how long the oldest has, and the exposure behind it. */
data class ApplicationStateSummary(
    val status: String,
    val count: Long,
    /** Oldest `createdAt` in this state — the item the desk should act on first. Null only when the
     *  state is empty, which the count already says. */
    val oldestCreatedAt: OffsetDateTime?,
    val requested: List<MoneyTotal>,
)

/** One loan status: how many, and the principal behind them. */
data class LoanStateSummary(val status: String, val count: Long, val principal: List<MoneyTotal>)
