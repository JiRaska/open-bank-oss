// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.model

/**
 * Expands the two ledger reads into positions (ADR-0314 D1, phase 0).
 *
 * - every sub-ledger balance becomes one [PositionKind.SUB_LEDGER] position on the
 *   deposit-control account of its currency;
 * - every trial-balance line that is NOT a deposit-control account becomes one
 *   [PositionKind.GL_ACCOUNT] position carrying the line's net.
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

    fun build(inputs: LedgerInputs, controlCodes: Set<String> = DEPOSIT_CONTROL_CODES): List<Position> {
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
        val glPositions = inputs.trialBalance
            .filter { it.glAccountCode !in controlCodes }
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
        return subLedgerPositions + glPositions
    }
}
