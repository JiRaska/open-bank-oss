// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.finrep.domain.model.TrialBalanceAssurance
import com.openbank.finrep.domain.model.TrialBalanceIdentity
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps ledger trial balance lines to the FINREP F 01.01 assets template.
 *
 * EBA Reporting Framework 4.2 defines total assets as r0380/c0010 (datapoint 32354). Liabilities
 * and equity are separate templates F 01.02 and F 01.03; placing them in this template would create
 * a syntactically plausible but semantically false regulatory fact (#6980).
 *
 * Ledger publishes asset debit balances as positive `net = totalDebit − totalCredit`, so the
 * official total-assets fact can be sourced directly. [TrialBalanceIdentity] still assesses the
 * complete snapshot; the preview never manufactures equity as a residual.
 */
object F0101Mapper {

    fun map(snapshot: TrialBalanceSnapshot, asOf: LocalDate): FinrepTemplate {
        val lines = FinrepCoverage.onBalanceSheet(snapshot.lines)
        // 1001/1002 nostro: a credit balance is an overdraft owed to the correspondent bank, i.e. a
        // liability (F01.02 r0150), not negative cash. It is taken out of the asset side per line, and
        // added back to total assets so r0380 stays the gross asset figure (see NostroOverdraft).
        val overdraft = NostroOverdraft.of(lines)
        val nostro = sumCodes(lines, "1001", "1002").add(overdraft)
        // 1110-1113 scheme settlement balances are clearing receivables of the same nature as 1100-1103.
        val demandDeposits = nostro.add(
            sumCodes(lines, "1100", "1101", "1102", "1103", "1110", "1111", "1112", "1113"),
        )
        // 1510: ČNB deposit facility = cash balances at central banks (r0030), not other demand deposits.
        val centralBank = sumCodes(lines, "1510")
        val cash = demandDeposits.add(centralBank)
        // 1500/1501: money-market placements with credit institutions are loans and advances at
        // amortised cost; 1520/1521 accrued interest receivable is part of that carrying amount
        // (IFRS 9). The accrual account is shared with the ČNB facility and cannot be split by
        // counterparty: an accepted limitation, ČNB accrued interest stays with loans (README).
        // ADR-0344 (V35): treasury loans 1660/1661 with accrual 1650/1651, reverse repo 1680/1681
        // with accrual 1685/1686 are loans and advances at amortised cost.
        val loans = sumCodes(
            lines,
            "1500", "1501", "1520", "1521",
            "1650", "1651", "1660", "1661",
            "1680", "1681", "1685", "1686",
            "1200", "1201", "1202", "1203",
            "1300", "1301", "1302", "1303",
            "1400", "1401", "1402", "1403",
        )
        // ADR-0344 securities at amortised cost (1600/1601), with unamortised premium/discount
        // (1630/1631) and accrued interest (1640/1641) as part of that carrying amount. The FVOCI
        // (1610), FVTPL (1620) and derivative (1670, 1700) books reach total assets r0380 only:
        // their category rows have no datapoint id verified against the pinned workbook yet, and
        // this template emits no unverified coordinate (Eba42DatapointFixtureTest, #14284).
        val acSecurities = sumCodes(lines, "1600", "1601", "1630", "1631", "1640", "1641")
        val otherAssets = sumCodes(lines, "1990", "1991", "1992", "1993", "1995", "1996", "1997", "1690", "1691")
        val totalAssets = sumNet(lines, "ASSET").add(overdraft)
        val cells = listOf(
            FinrepCell(rowRef = "r0010", colRef = "c0010", value = cash),
            FinrepCell(rowRef = "r0030", colRef = "c0010", value = centralBank),
            FinrepCell(rowRef = "r0040", colRef = "c0010", value = demandDeposits),
            FinrepCell(rowRef = "r0181", colRef = "c0010", value = loans.add(acSecurities)),
            FinrepCell(rowRef = "r0183", colRef = "c0010", value = loans),
            FinrepCell(rowRef = "r0360", colRef = "c0010", value = otherAssets),
            FinrepCell(rowRef = "r0380", colRef = "c0010", value = totalAssets),
        )

        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F01.01",
            period = asOf,
            cells = cells,
            dataGaps = FinrepCoverage.gapsFor("F01.01", lines),
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }

    private fun sumNet(lines: List<TrialBalanceLineDto>, vararg accountTypes: String): BigDecimal = lines
        .filter { it.accountType in accountTypes }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }

    private fun sumCodes(lines: List<TrialBalanceLineDto>, vararg codes: String): BigDecimal = lines
        .filter { it.code in codes }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }
}
