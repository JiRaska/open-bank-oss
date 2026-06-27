// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class InterestRateType { FIXED, VARIABLE, TIERED }
enum class AccrualStatus { ACCRUING, CAPITALIZED, REVERSED, SUSPENDED }
enum class DayCount { ACT_365, ACT_360, ACT_ACT, THIRTY_360 }

data class InterestRateConfig(
    val id: UUID = UUID.randomUUID(),
    val productId: String,
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
    val accrualCount: Int
)
