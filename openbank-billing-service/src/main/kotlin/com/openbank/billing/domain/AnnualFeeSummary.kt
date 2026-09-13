// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.domain

import java.math.BigDecimal

/**
 * One itemized fee line of a [AnnualFeeSummary] (PAD (EU) 2014/92 Art. 5 / Annex II, ADR-0248):
 * every charge of one fee ([code] = [AssessedFee.feeId]) across the whole year, summed into a
 * single total.
 *
 * [category] is a **known gap**, not a fabricated field: this service has no persisted PAD Annex
 * II taxonomy for its fee catalog — [BillableFee.type] is the closest candidate, but it is never
 * carried onto [AssessedFee]/`AssessedFeeEntity`, only used transiently at assessment time. ADR-0248
 * itself flags "the exact mapping from this platform's fee catalog to PAD Annex II's standardized
 * terminology needs legal review before this template goes to production" as an open item — until
 * that mapping exists, [category] reuses [name] rather than inventing a taxonomy this service does
 * not own.
 */
data class AnnualFeeSummaryLine(val code: String, val name: String, val category: String, val amount: BigDecimal)

/**
 * The PAD Art. 5 annual statement of fees for one account/calendar year (ADR-0248), aggregated
 * from every [AssessedFee] billing-service actually posted (and never reversed) in that year.
 *
 * [interestRate] is a **known gap, not a fabricated field**: billing-service's domain
 * ([BillableFee], [AssessedFee], [BillingCycle]) carries no debit/credit interest rate anywhere —
 * that data lives in `openbank-interest-service`, which billing-service has no port to read as of
 * this ADR. `null` here means "no source for this field today", not "this account has no rate".
 */
data class AnnualFeeSummary(
    val accountId: String,
    val partyRef: String,
    val year: Int,
    val currency: String,
    val fees: List<AnnualFeeSummaryLine>,
    val totalFees: BigDecimal,
    val interestRate: BigDecimal?,
) {
    companion object {
        /**
         * Aggregates [candidateFees] into one [AnnualFeeSummaryLine] per fee code, summing every
         * charge of that fee across the year's billing cycles (a monthly maintenance fee charged
         * 12 times becomes one line with the year's total). Grouped by `(feeId, name)` rather than
         * `feeId` alone so a fee whose display name changed mid-year (a catalog edit) never
         * silently merges two different-looking charges into one row — the natural key `feeId` is
         * still what [AnnualFeeSummaryLine.code] reports.
         *
         * **Filters to [PostingStatus.POSTED] itself** — defense in depth, not a repeat of the
         * repository's own query filter: the repository (`postedFeesForAccount`) is expected to
         * pass only POSTED rows already, but this function does not trust that as its sole
         * safeguard, so a waived fee (`NOT_APPLICABLE`), one still in flight (`PENDING`/`FAILED`),
         * or one under/already reversed (`REVERSAL_PENDING`/`REVERSED`) is excluded here too even
         * if a future caller forgets to filter. This is also what makes the exclusion rule directly
         * unit-testable without a database.
         */
        fun aggregate(
            accountId: String,
            partyRef: String,
            year: Int,
            currency: String,
            candidateFees: List<AssessedFee>,
            interestRate: BigDecimal?,
        ): AnnualFeeSummary {
            val lines = candidateFees
                .filter { it.postingStatus == PostingStatus.POSTED }
                .groupBy { it.feeId to it.name }
                .map { (key, fees) ->
                    val (feeId, name) = key
                    AnnualFeeSummaryLine(
                        code = feeId,
                        name = name,
                        category = name,
                        amount = fees.fold(BigDecimal.ZERO) { acc, fee -> acc + fee.chargedAmount },
                    )
                }
                .sortedBy { it.code }
            val total = lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
            return AnnualFeeSummary(
                accountId = accountId,
                partyRef = partyRef,
                year = year,
                currency = currency,
                fees = lines,
                totalFees = total,
                interestRate = interestRate,
            )
        }
    }
}
