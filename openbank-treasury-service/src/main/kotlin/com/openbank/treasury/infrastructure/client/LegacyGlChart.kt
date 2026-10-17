// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.openbank.treasury.domain.model.PostingLine
import java.util.UUID

/**
 * ROLLOUT SHIM, not a chart: the accounts the ledger's accountingClass map resolves treasury's
 * classes to, as the fixed ids treasury used to send before ADR-0341 D3. It is used ONLY while
 * `openbank.treasury.ledger.post-by-class` is false (the default), so that deploying treasury before
 * a ledger that serves classes cannot fail a posting: the payload stays the explicit `glAccountId`
 * an old ledger understands. Flip the flag once the ledger carrying V31 is live, then delete this
 * file. `LegacyGlChartTest` pins it to the same table as the ledger migration so the two cannot
 * drift while both exist.
 */
internal object LegacyGlChart {
    private val codes: Map<Triple<String, String, String>, String> = listOf(
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

    val entries: Map<Triple<String, String, String>, String> get() = codes

    fun glAccountId(line: PostingLine): UUID {
        val code = requireNotNull(codes[Triple(line.accountingClass, line.role.name, line.currency)]) {
            "no explicit GL account for ${line.accountingClass}/${line.role}/${line.currency}"
        }
        return UUID.fromString("a0000000-0000-0000-0000-00000000$code")
    }
}
