// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class TrialBalanceLine(
    val glAccountId: UUID,
    val code: String,
    val name: String,
    val type: GlAccountType,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
) {
    val net: BigDecimal get() = totalDebit.subtract(totalCredit)
}

/**
 * One customer account's standing in the deposit-control sub-ledger (analytická evidence,
 * ADR-0039 Phase B). Deposit control is a credit-normal liability, so the amount the bank owes
 * the customer is credit − debit; that is what must tie out against the balance read-model.
 */
data class SubLedgerBalance(
    val subAccountId: UUID,
    val currency: String,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
) {
    /** Customer-owed booked amount: credit − debit (liability, credit-normal). */
    val net: BigDecimal get() = totalCredit.subtract(totalDebit)
}

/**
 * [scope] is carried on the result, not merely applied to it: a trial balance that does not say
 * which population it counted is indistinguishable from one that counted the wrong population, and
 * this one excludes synthetic activity by default (ADR-0252, [LedgerScope]).
 */
data class TrialBalance(
    val asOf: LocalDate,
    val lines: List<TrialBalanceLine>,
    val scope: LedgerScope = LedgerScope.REAL_ONLY,
) {
    val totalDebit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalDebit) }
    val totalCredit: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.totalCredit) }

    /** A correct double-entry ledger must always balance: total debits == total credits. */
    val isBalanced: Boolean get() = totalDebit.compareTo(totalCredit) == 0
}

/**
 * Tie-out result for one deposit-control GL account (ADR-0039 Phase B).
 *
 * A control account *ties out* when the sum of all per-customer sub-ledger entries equals
 * the aggregate GL balance for that control account. A non-zero [delta] means there are
 * lines that lack a sub_account_id (orphan entries) or sub-ledger arithmetic diverged from
 * the GL — both are incidents that require investigation.
 *
 * @param controlAccountId  GL account UUID (one of the deposit-control accounts 2100–2103)
 * @param currency          ISO-4217 currency code
 * @param glNet             GL aggregate: `totalCredit − totalDebit` over ALL posted lines
 * @param subLedgerNet      Sum of per-customer nets: `Σ(credit − debit)` over all sub-ledger lines
 * @param delta             `glNet − subLedgerNet` (zero = tie-out OK, non-zero = break)
 * @param lines             Individual sub-account balances that make up [subLedgerNet]
 */
data class ControlAccountTieOut(
    val controlAccountId: UUID,
    val currency: String,
    val asOf: LocalDate,
    val glNet: BigDecimal,
    val subLedgerNet: BigDecimal,
    val delta: BigDecimal,
    val lines: List<SubLedgerBalance>,
) {
    val isTiedOut: Boolean get() = delta.compareTo(BigDecimal.ZERO) == 0
}
