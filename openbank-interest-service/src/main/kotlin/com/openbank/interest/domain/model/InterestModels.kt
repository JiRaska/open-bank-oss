// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class InterestRateType { FIXED, VARIABLE, TIERED }

/**
 * Lifecycle of one daily accrual.
 *
 * `ACCRUING` → `CAPITALIZING` → `CAPITALIZED` is the money path. `CAPITALIZING` is the **claim**:
 * `InterestService.capitalize` freezes the exact set of accruals it is about to credit BEFORE it
 * posts to the ledger, so the amount the ledger books and the amount the capitalization row records
 * can never diverge — see `InterestService.capitalize` for the crash/recovery argument.
 *
 * `REVERSED`/`SUSPENDED` are declared but have no writer yet.
 */
enum class AccrualStatus { ACCRUING, CAPITALIZING, CAPITALIZED, REVERSED, SUSPENDED }
enum class DayCount { ACT_365, ACT_360, ACT_ACT, THIRTY_360 }

data class InterestRateConfig(
    val id: UUID = UUID.randomUUID(),
    val productId: String,
    /** When set, this rate is an override for that single account, taking precedence over the
     *  product-level default (accountId == null). Lets a specific customer be granted interest on an
     *  otherwise non-interest-bearing account (e.g. a CURRENT account, which defaults to 0). */
    val accountId: UUID? = null,
    /** The currency this rate applies to. Rates are currency-specific — a CZK savings rate is not an
     *  EUR one — so resolution is (account/product, currency)-specific and an account can only accrue
     *  in a currency it has a rate config for (issue #1265). */
    val currency: String,
    val rateType: InterestRateType = InterestRateType.FIXED,
    val annualRate: BigDecimal,
    val minBalance: BigDecimal = BigDecimal.ZERO,
    val maxBalance: BigDecimal? = null,
    val dayCount: DayCount = DayCount.ACT_365,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate? = null,
    val active: Boolean = true,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class InterestAccrual(
    val id: UUID = UUID.randomUUID(),
    val accountId: UUID,
    val productId: String,
    val configId: UUID,
    val accrualDate: LocalDate,
    val balance: BigDecimal,
    val dailyRate: BigDecimal,
    val accruedAmount: BigDecimal,
    val currency: String = "EUR",
    val status: AccrualStatus = AccrualStatus.ACCRUING,
    /**
     * The `periodTo` this accrual was claimed for when it went `CAPITALIZING`, and null otherwise.
     *
     * It is what makes a claim recoverable: `capitalize(account, product, toDate)` completes an
     * in-flight claim only when [claimedPeriodTo] equals the requested `toDate`, because the period
     * end is part of the ledger idempotency key. Capitalizing a claimed set to a *different* period
     * end would mint a second key and post a SECOND journal for interest the first attempt may
     * already have booked.
     */
    val claimedPeriodTo: LocalDate? = null,
    val capitalizedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
)

data class InterestCapitalization(
    val id: UUID = UUID.randomUUID(),
    val accountId: UUID,
    val productId: String,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val totalAccrued: BigDecimal,
    /**
     * The amount actually credited to the customer. Per ADR-0033 §D this is the **net** amount
     * (`grossAmount − taxAmount`); for non-withheld credits it equals [grossAmount].
     */
    val capitalizedAmount: BigDecimal,
    /** Gross interest before withholding (the summed period accruals, scaled to currency minor units). */
    val grossAmount: BigDecimal = capitalizedAmount,
    /** Withholding tax retained at source (whole CZK, 0 when not withheld). ADR-0033 §B. */
    val taxAmount: BigDecimal = BigDecimal.ZERO,
    /** Net amount credited (= [capitalizedAmount]); carried explicitly for reporting clarity. */
    val netAmount: BigDecimal = capitalizedAmount,
    val currency: String = "EUR",
    val ledgerEntryId: UUID? = null,
    val createdAt: OffsetDateTime,
)

data class AccrualRequest(
    val accountId: UUID,
    val productId: String,
    val balance: BigDecimal,
    val currency: String = "EUR",
    val accrualDate: LocalDate,
)

data class AccrualSummary(
    val accountId: UUID,
    val totalAccrued: BigDecimal,
    val currency: String,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val accrualCount: Int,
)
