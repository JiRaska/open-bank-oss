// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.model.FinrepDataGap

/** Fail-closed coverage boundary for the EBA Reporting Framework 4.2 mappings (#6980). */
object FinrepCoverage {
    private val classifiedAccounts = setOf(
        "1000", "1001",
        "1100", "1101", "1102", "1103",
        "1200", "1201", "1202", "1203",
        "1300", "1301", "1302", "1303",
        "1400", "1401", "1402", "1403",
        "1990", "1991", "1992", "1993", "1995", "1996", "1997",
        // Treasury / money-market chart (ADR-0315, ledger V29/V30): nostro EUR, placements with banks,
        // ČNB deposit facility, accrued interest receivable.
        "1002", "1500", "1501", "1510", "1520", "1521",
        "2000", "2001", "2002", "2100", "2101", "2102", "2103", "2200",
        // Treasury: borrowings from banks, accrued interest payable, ČNB lombard borrowing.
        "2300", "2301", "2310", "2311", "2320",
        "3000", "4000", "4001", "4002", "4003",
        "4010", "4011", "4012", "4013",
        "4100", "4101", "4102", "4103",
        "4200", "4201", // treasury money-market interest income (CZK, EUR)
        "5100", "5101", "5102", "5103", "5200", "5201", "5900", // 5200/5201: treasury interest expense
        "6000", "6010", "6020", "6030", "6040", "6050", "6060",
        // Scheme settlement (ledger V26) and per-currency fee income (V34).
        "1110", "1111", "1112", "1113", "4004", "4005", "4006",
        // ADR-0344 treasury instrument chart (ledger V35, #14284). Rows: see the F01/F02 mappers.
        "1600", "1601", "1610", "1611", "1620", "1621", "1630", "1631", "1640", "1641",
        "1650", "1651", "1660", "1661", "1670", "1671", "1680", "1681", "1685", "1686",
        "1690", "1691", "1700", "1701",
        "2400", "2401", "2410", "2411", "2420", "2421", "2430", "2431",
        "3100", "3101",
        "4210", "4211", "4220", "4221", "4230", "4231", "4240", "4241",
        "4300", "4301", "4310", "4311",
        "5230", "5231", "5240", "5241", "5300", "5301", "5310", "5311",
    )

    /**
     * ADR-0344 memo pairs (ledger V35): forward-start commitments (9000/9100), collateral received
     * (9010/9110) and pledged (9020/9120). The chart has no off-balance type, so each memo asset is
     * typed ASSET with a LIABILITY contra. They are off-balance-sheet: not assets or liabilities
     * under IFRS 9 and never a FINREP F 01 or F 02 fact. They are governed (no data gap) and removed
     * from every template's figures by [onBalanceSheet]; counting them would inflate total assets
     * and total liabilities by the same notional.
     */
    private val offBalanceMemoAccounts = setOf(
        "9000", "9001", "9010", "9011", "9020", "9021",
        "9100", "9101", "9110", "9111", "9120", "9121",
    )

    /** The lines that are balance-sheet or P&L facts: everything except the off-balance memo pairs. */
    fun onBalanceSheet(lines: List<TrialBalanceLineDto>): List<TrialBalanceLineDto> =
        lines.filterNot { it.code in offBalanceMemoAccounts }

    /** True when [code] has a governed EBA 4.2 mapping (on-balance) or is a declared memo account. */
    fun isGoverned(code: String): Boolean = code in classifiedAccounts || code in offBalanceMemoAccounts

    fun gapsFor(templateId: String, lines: List<TrialBalanceLineDto>): List<FinrepDataGap> {
        val unknown = lines.map { it.code }.filterNot(::isGoverned).distinct().sorted()
        return if (unknown.isEmpty()) {
            emptyList()
        } else {
            listOf(
                FinrepDataGap(
                    code = "UNCLASSIFIED_GL_ACCOUNT",
                    affectedScope = "$templateId: ${unknown.joinToString()}",
                    reason = "The trial balance contains an active GL account with no governed EBA 4.2 mapping. " +
                        "The return remains blocked until the chart classification is extended.",
                ),
            )
        }
    }
}
