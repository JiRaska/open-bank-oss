// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

/**
 * Expands the ledger reads and, when read, lending's loan book into positions (ADR-0314 D1, D4).
 *
 * - every sub-ledger balance becomes one [PositionKind.SUB_LEDGER] position on the
 *   deposit-control account of its currency;
 * - when the loan book was read, every loan becomes one [PositionKind.LOAN] position on the GL
 *   account lending names for it (Loans Receivable 1200 CZK / 1201 EUR / 1202 USD / 1203 GBP),
 *   carrying its outstanding principal;
 * - every trial-balance line that is NOT a deposit-control account — nor, when the loan book was
 *   read, a Loans Receivable account — becomes one [PositionKind.GL_ACCOUNT] position carrying the
 *   line's net.
 *
 * The same rule applies to Loans Receivable as to deposit control: once loans are read, those
 * accounts are NEVER also carried at GL level, even with no loans at all. Otherwise the GL figure
 * would stand in for a missing loan and the tie-out would pass by construction. When the book was
 * NOT read (lending read disabled), the accounts stay GL-level exactly as before this slice.
 *
 * Interest Receivable (13xx) and Loan Loss Allowance (14xx) stay GL-level: lending posts them per
 * loan but the loan-book read carries no per-loan accrual or allowance balance to tie them to.
 *
 * A deposit-control line is deliberately never turned into a GL-level position, even when it has
 * no sub-ledger rows at all. Doing so would let the GL figure stand in for the missing customer
 * breakdown and tie out by construction — the one case the gate exists to catch.
 */
object PositionBuilder {

    /**
     * Deposit-control codes, one per currency (2100 CZK, 2101 EUR, 2102 USD, 2103 GBP). Mirrors
     * `GlAccount.DEPOSIT_CONTROL_CODES` in ledger-service, which is the only place a sub-account
     * id may be posted.
     */
    val DEPOSIT_CONTROL_CODES: Set<String> = setOf("2100", "2101", "2102", "2103")

    /**
     * Loans Receivable codes, one per currency. Mirrors ledger V19/V20 and lending's
     * `LendingGlChart`, which is where a loan's principal is posted.
     */
    val LOANS_RECEIVABLE_CODES: Set<String> = setOf("1200", "1201", "1202", "1203")

    fun build(
        inputs: LedgerInputs,
        loans: List<Instrument>? = null,
        controlCodes: Set<String> = DEPOSIT_CONTROL_CODES,
    ): List<Position> {
        val typeByCode = inputs.trialBalance.associate { it.glAccountCode to it.glAccountType }
        val contractCodes = if (loans == null) controlCodes else controlCodes + LOANS_RECEIVABLE_CODES
        val controlByCurrency = inputs.trialBalance
            .filter { it.glAccountCode in controlCodes }
            .groupBy { it.currency }
            // Exactly one control account per currency is the ledger's invariant. If it ever
            // breaks, guessing which one a customer balance belongs to would fabricate a tie-out,
            // so an ambiguous currency is treated as unmapped.
            .mapNotNull { (currency, lines) -> lines.singleOrNull()?.let { currency to it } }
            .toMap()

        val subLedgerPositions = inputs.subLedger.map { balance ->
            val control = controlByCurrency[balance.currency]
            Position(
                kind = PositionKind.SUB_LEDGER,
                glAccountCode = control?.glAccountCode,
                glAccountType = control?.glAccountType,
                currency = balance.currency,
                subAccountId = balance.subAccountId,
                amount = balance.totalDebit.subtract(balance.totalCredit),
            )
        }
        val loanPositions = loans.orEmpty().map { loan ->
            Position(
                kind = PositionKind.LOAN,
                glAccountCode = loan.glAccountCode,
                glAccountType = loan.glAccountCode?.let { typeByCode[it] ?: "ASSET" },
                currency = loan.currency,
                subAccountId = null,
                amount = loan.outstanding,
                instrumentId = loan.id,
            )
        }
        val glPositions = inputs.trialBalance
            .filter { it.glAccountCode !in contractCodes }
            .map { line ->
                Position(
                    kind = PositionKind.GL_ACCOUNT,
                    glAccountCode = line.glAccountCode,
                    glAccountType = line.glAccountType,
                    currency = line.currency,
                    subAccountId = null,
                    amount = line.net,
                )
            }
        return subLedgerPositions + loanPositions + glPositions
    }
}
