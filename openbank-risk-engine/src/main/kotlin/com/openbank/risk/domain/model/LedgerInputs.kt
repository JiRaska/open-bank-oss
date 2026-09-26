// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One GL account line of the ledger trial balance, in the ledger's own sign convention:
 * [net] = totalDebit − totalCredit (so a credit-normal liability is negative).
 */
data class TrialBalanceLine(
    val glAccountCode: String,
    val glAccountType: String,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
    val net: BigDecimal,
)

/**
 * One customer account's standing in the deposit-control sub-ledger (ADR-0039 Phase B).
 *
 * The ledger groups these by (subAccountId, currency) and does NOT name the GL account: a
 * sub-account id is only ever carried on a deposit-control leg, of which there is exactly one
 * per currency. [PositionBuilder] recovers the GL account from that fact.
 */
data class SubLedgerBalance(
    val subAccountId: UUID,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
)

/** Both ledger reads a snapshot is built from, as they arrived. */
data class LedgerInputs(
    val asOf: LocalDate,
    val trialBalance: List<TrialBalanceLine>,
    val subLedger: List<SubLedgerBalance>,
)
