// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.util.UUID

/**
 * What a posting leg is, within an accounting class (ADR-0341 D3). A class names the product's
 * economics (INTERBANK_PLACEMENT); the role names which of that product's ledger accounts the leg
 * belongs to. Closed on purpose: the same set is a CHECK constraint on the map table.
 */
enum class AccountingRole {
    PRINCIPAL,
    ACCRUED_INTEREST,
    INTEREST_INCOME,
    INTEREST_EXPENSE,
    FEE_INCOME,
}

/** One row of the ledger-owned accountingClass -> GL account map (ADR-0341 D3). */
data class AccountingClassMapping(
    val accountingClass: String,
    val role: AccountingRole,
    val currencyCode: String,
    val glAccountId: UUID,
    val glCode: String,
)
