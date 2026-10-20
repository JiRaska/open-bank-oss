// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.client

import com.openbank.treasury.application.port.out.NostroGl
import com.openbank.treasury.domain.model.PostingLine
import java.util.UUID

/**
 * ROLLOUT SHIM, not a chart: the accounts the ledger's accountingClass map resolves treasury's
 * classes to, as the fixed ids treasury used to send before ADR-0341 D3. It is used ONLY while
 * `openbank.treasury.ledger.post-by-class` is false (the default), so that deploying treasury before
 * a ledger that serves classes cannot fail a posting: the payload stays the explicit `glAccountId`
 * an old ledger understands. Flip the flag once the ledger carrying V32 is live, then delete this
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
        // ADR-0344 D7: the posting-rule matrix classes (ledger V34).
        row("CASH_IN_TRANSIT", "PRINCIPAL", "CZK", "1690"),
        row("CASH_IN_TRANSIT", "PRINCIPAL", "EUR", "1691"),
        row("TREASURY_LOAN", "PRINCIPAL", "CZK", "1660"),
        row("TREASURY_LOAN", "PRINCIPAL", "EUR", "1661"),
        row("TREASURY_LOAN", "ACCRUED_INTEREST", "CZK", "1650"),
        row("TREASURY_LOAN", "ACCRUED_INTEREST", "EUR", "1651"),
        row("TREASURY_LOAN", "INTEREST_INCOME", "CZK", "4220"),
        row("TREASURY_LOAN", "INTEREST_INCOME", "EUR", "4221"),
        row("DEBT_SECURITY_AMORTISED_COST", "PRINCIPAL", "CZK", "1600"),
        row("DEBT_SECURITY_AMORTISED_COST", "PRINCIPAL", "EUR", "1601"),
        row("DEBT_SECURITY_AMORTISED_COST", "PREMIUM_DISCOUNT", "CZK", "1630"),
        row("DEBT_SECURITY_AMORTISED_COST", "PREMIUM_DISCOUNT", "EUR", "1631"),
        row("DEBT_SECURITY_AMORTISED_COST", "ACCRUED_INTEREST", "CZK", "1640"),
        row("DEBT_SECURITY_AMORTISED_COST", "ACCRUED_INTEREST", "EUR", "1641"),
        row("DEBT_SECURITY_AMORTISED_COST", "INTEREST_INCOME", "CZK", "4210"),
        row("DEBT_SECURITY_AMORTISED_COST", "INTEREST_INCOME", "EUR", "4211"),
        row("DEBT_SECURITY_FVOCI", "PRINCIPAL", "CZK", "1610"),
        row("DEBT_SECURITY_FVOCI", "PRINCIPAL", "EUR", "1611"),
        row("DEBT_SECURITY_FVOCI", "PREMIUM_DISCOUNT", "CZK", "1630"),
        row("DEBT_SECURITY_FVOCI", "PREMIUM_DISCOUNT", "EUR", "1631"),
        row("DEBT_SECURITY_FVOCI", "ACCRUED_INTEREST", "CZK", "1640"),
        row("DEBT_SECURITY_FVOCI", "ACCRUED_INTEREST", "EUR", "1641"),
        row("DEBT_SECURITY_FVOCI", "INTEREST_INCOME", "CZK", "4210"),
        row("DEBT_SECURITY_FVOCI", "INTEREST_INCOME", "EUR", "4211"),
        row("DEBT_SECURITY_FVTPL", "PRINCIPAL", "CZK", "1620"),
        row("DEBT_SECURITY_FVTPL", "PRINCIPAL", "EUR", "1621"),
        row("DEBT_SECURITY_FVTPL", "PREMIUM_DISCOUNT", "CZK", "1630"),
        row("DEBT_SECURITY_FVTPL", "PREMIUM_DISCOUNT", "EUR", "1631"),
        row("DEBT_SECURITY_FVTPL", "ACCRUED_INTEREST", "CZK", "1640"),
        row("DEBT_SECURITY_FVTPL", "ACCRUED_INTEREST", "EUR", "1641"),
        row("DEBT_SECURITY_FVTPL", "INTEREST_INCOME", "CZK", "4210"),
        row("DEBT_SECURITY_FVTPL", "INTEREST_INCOME", "EUR", "4211"),
        row("DEBT_SECURITY_FVOCI", "REVALUATION_RESERVE", "CZK", "3100"),
        row("DEBT_SECURITY_FVOCI", "REVALUATION_RESERVE", "EUR", "3101"),
        row("REPO_BORROWING", "PRINCIPAL", "CZK", "2430"),
        row("REPO_BORROWING", "PRINCIPAL", "EUR", "2431"),
        row("REPO_BORROWING", "ACCRUED_INTEREST", "CZK", "2420"),
        row("REPO_BORROWING", "ACCRUED_INTEREST", "EUR", "2421"),
        row("REPO_BORROWING", "INTEREST_EXPENSE", "CZK", "5240"),
        row("REPO_BORROWING", "INTEREST_EXPENSE", "EUR", "5241"),
        row("REVERSE_REPO_LENDING", "PRINCIPAL", "CZK", "1680"),
        row("REVERSE_REPO_LENDING", "PRINCIPAL", "EUR", "1681"),
        row("REVERSE_REPO_LENDING", "ACCRUED_INTEREST", "CZK", "1685"),
        row("REVERSE_REPO_LENDING", "ACCRUED_INTEREST", "EUR", "1686"),
        row("REVERSE_REPO_LENDING", "INTEREST_INCOME", "CZK", "4240"),
        row("REVERSE_REPO_LENDING", "INTEREST_INCOME", "EUR", "4241"),
        row("DERIVATIVE_ASSET", "FAIR_VALUE", "CZK", "1700"),
        row("DERIVATIVE_ASSET", "FAIR_VALUE", "EUR", "1701"),
        row("DERIVATIVE_LIABILITY", "FAIR_VALUE", "CZK", "2400"),
        row("DERIVATIVE_LIABILITY", "FAIR_VALUE", "EUR", "2401"),
        row("DERIVATIVE_RECEIVE_LEG", "ACCRUED_INTEREST", "CZK", "1670"),
        row("DERIVATIVE_RECEIVE_LEG", "ACCRUED_INTEREST", "EUR", "1671"),
        row("DERIVATIVE_RECEIVE_LEG", "INTEREST_INCOME", "CZK", "4230"),
        row("DERIVATIVE_RECEIVE_LEG", "INTEREST_INCOME", "EUR", "4231"),
        row("DERIVATIVE_PAY_LEG", "ACCRUED_INTEREST", "CZK", "2410"),
        row("DERIVATIVE_PAY_LEG", "ACCRUED_INTEREST", "EUR", "2411"),
        row("DERIVATIVE_PAY_LEG", "INTEREST_EXPENSE", "CZK", "5230"),
        row("DERIVATIVE_PAY_LEG", "INTEREST_EXPENSE", "EUR", "5231"),
        row("TREASURY_TRADING_RESULT", "REALISED_GAIN", "CZK", "4300"),
        row("TREASURY_TRADING_RESULT", "REALISED_GAIN", "EUR", "4301"),
        row("TREASURY_TRADING_RESULT", "REALISED_LOSS", "CZK", "5300"),
        row("TREASURY_TRADING_RESULT", "REALISED_LOSS", "EUR", "5301"),
        row("TREASURY_TRADING_RESULT", "UNREALISED_GAIN", "CZK", "4310"),
        row("TREASURY_TRADING_RESULT", "UNREALISED_GAIN", "EUR", "4311"),
        row("TREASURY_TRADING_RESULT", "UNREALISED_LOSS", "CZK", "5310"),
        row("TREASURY_TRADING_RESULT", "UNREALISED_LOSS", "EUR", "5311"),
        row("FORWARD_COMMITMENT", "MEMO", "CZK", "9000"),
        row("FORWARD_COMMITMENT", "MEMO", "EUR", "9001"),
        row("FORWARD_COMMITMENT", "MEMO_CONTRA", "CZK", "9100"),
        row("FORWARD_COMMITMENT", "MEMO_CONTRA", "EUR", "9101"),
        row("COLLATERAL_RECEIVED", "MEMO", "CZK", "9010"),
        row("COLLATERAL_RECEIVED", "MEMO", "EUR", "9011"),
        row("COLLATERAL_RECEIVED", "MEMO_CONTRA", "CZK", "9110"),
        row("COLLATERAL_RECEIVED", "MEMO_CONTRA", "EUR", "9111"),
        row("COLLATERAL_PLEDGED", "MEMO", "CZK", "9020"),
        row("COLLATERAL_PLEDGED", "MEMO", "EUR", "9021"),
        row("COLLATERAL_PLEDGED", "MEMO_CONTRA", "CZK", "9120"),
        row("COLLATERAL_PLEDGED", "MEMO_CONTRA", "EUR", "9121"),
    ).toMap()

    private fun row(accountingClass: String, role: String, currency: String, code: String) =
        Triple(accountingClass, role, currency) to code

    val entries: Map<Triple<String, String, String>, String> get() = codes

    /** The nostro accounts by currency (class NOSTRO_ACCOUNT, role PRINCIPAL), for reads against an older ledger. */
    fun nostroAccounts(): Map<String, NostroGl> = codes
        .filterKeys { it.first == "NOSTRO_ACCOUNT" && it.second == "PRINCIPAL" }
        .entries.associate { (key, code) -> key.third to NostroGl(code, idOf(code)) }

    private fun idOf(code: String): UUID = UUID.fromString("a0000000-0000-0000-0000-00000000$code")

    fun glAccountId(line: PostingLine): UUID {
        val code = requireNotNull(codes[Triple(line.accountingClass, line.role.name, line.currency)]) {
            "no explicit GL account for ${line.accountingClass}/${line.role}/${line.currency}"
        }
        return UUID.fromString("a0000000-0000-0000-0000-00000000$code")
    }
}
