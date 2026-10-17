// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.model.PostingLine

/**
 * The treasury rows of the ledger's `accounting_class_gl_map` (migration V31), as a literal.
 * `AccountingClassGlMapIT` in openbank-ledger-service holds the same table against the real
 * database, so the chain "new rules + this map == the legacy chart" (see
 * [PostingJournalsUnchangedTest]) and "this map == the migration" together prove the journals land
 * on the same GL accounts as before. Keep the two copies identical.
 */
object LedgerMapGolden {
    /** (accountingClass, role, currency) -> GL code. */
    val treasury: Map<Triple<String, String, String>, String> = listOf(
        row("NOSTRO_ACCOUNT", "PRINCIPAL", "CZK", "1001"),
        row("NOSTRO_ACCOUNT", "PRINCIPAL", "EUR", "1002"),
        row("INTERBANK_PLACEMENT", "PRINCIPAL", "CZK", "1500"),
        row("INTERBANK_PLACEMENT", "PRINCIPAL", "EUR", "1501"),
        row("INTERBANK_PLACEMENT", "ACCRUED_INTEREST", "CZK", "1520"),
        row("INTERBANK_PLACEMENT", "ACCRUED_INTEREST", "EUR", "1521"),
        row("INTERBANK_PLACEMENT", "INTEREST_INCOME", "CZK", "4200"),
        row("INTERBANK_PLACEMENT", "INTEREST_INCOME", "EUR", "4201"),
        row("INTERBANK_BORROWING", "PRINCIPAL", "CZK", "2300"),
        row("INTERBANK_BORROWING", "PRINCIPAL", "EUR", "2301"),
        row("INTERBANK_BORROWING", "ACCRUED_INTEREST", "CZK", "2310"),
        row("INTERBANK_BORROWING", "ACCRUED_INTEREST", "EUR", "2311"),
        row("INTERBANK_BORROWING", "INTEREST_EXPENSE", "CZK", "5200"),
        row("INTERBANK_BORROWING", "INTEREST_EXPENSE", "EUR", "5201"),
        row("CNB_DEPOSIT_FACILITY", "PRINCIPAL", "CZK", "1510"),
        row("CNB_DEPOSIT_FACILITY", "ACCRUED_INTEREST", "CZK", "1520"),
        row("CNB_DEPOSIT_FACILITY", "INTEREST_INCOME", "CZK", "4200"),
        row("CNB_LOMBARD_BORROWING", "PRINCIPAL", "CZK", "2320"),
        row("CNB_LOMBARD_BORROWING", "ACCRUED_INTEREST", "CZK", "2310"),
        row("CNB_LOMBARD_BORROWING", "INTEREST_EXPENSE", "CZK", "5200"),
        row("FX_SPOT_POSITION", "PRINCIPAL", "CZK", "1990"),
        row("FX_SPOT_POSITION", "PRINCIPAL", "EUR", "1991"),
    ).toMap()

    private fun row(accountingClass: String, role: String, currency: String, code: String) =
        Triple(accountingClass, role, currency) to code

    /** What the ledger would resolve this leg to; an unmapped leg is a test failure, not a default. */
    fun glCode(line: PostingLine): String = requireNotNull(
        treasury[Triple(line.accountingClass, line.role.name, line.currency)],
    ) { "the ledger map has no row for $line" }
}

/** The legacy-code view of a new-style leg, so existing assertions keep reading in GL codes. */
fun PostingLine.glCode(): String = LedgerMapGolden.glCode(this)
