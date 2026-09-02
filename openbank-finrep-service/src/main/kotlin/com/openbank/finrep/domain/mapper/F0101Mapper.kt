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
        val lines = snapshot.lines
        val cash = sumCodes(lines, "1001", "1100", "1101", "1102", "1103")
        val loans = sumCodes(
            lines,
            "1200", "1201", "1202", "1203",
            "1300", "1301", "1302", "1303",
            "1400", "1401", "1402", "1403",
        )
        val otherAssets = sumCodes(lines, "1990", "1991", "1992", "1993", "1995", "1996", "1997")
        val totalAssets = sumNet(lines, "ASSET")
        val cells = listOf(
            FinrepCell(rowRef = "r0010", colRef = "c0010", value = cash),
            FinrepCell(rowRef = "r0040", colRef = "c0010", value = cash),
            FinrepCell(rowRef = "r0181", colRef = "c0010", value = loans),
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
