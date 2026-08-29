// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.application.port.out.TrialBalanceSnapshot
import com.openbank.finrep.domain.model.BalanceVerdict
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.finrep.domain.model.TrialBalanceAssurance
import java.math.BigDecimal
import java.time.LocalDate

/** Official FINREP F 01.03 equity totals from EBA Reporting Framework 4.2 (#6980). */
object F0103Mapper {
    fun map(snapshot: TrialBalanceSnapshot, asOf: LocalDate): FinrepTemplate {
        fun sum(vararg accountTypes: String): BigDecimal = snapshot.lines
            .filter { it.accountType in accountTypes }
            .fold(BigDecimal.ZERO) { total, line -> total.add(line.net) }

        val capital = creditBalance(snapshot.lines, "6000")
        val sharePremium = creditBalance(snapshot.lines, "6010")
        val retainedEarnings = creditBalance(snapshot.lines, "6020")
        val otherReserves = creditBalance(snapshot.lines, "6030").subtract(debitBalance(snapshot.lines, "6040"))
        val otherInstruments = creditBalance(snapshot.lines, "6050", "6060")
        val currentProfit = sum("INCOME", "EXPENSE").negate()
        val equity = sum("EQUITY", "INCOME", "EXPENSE").negate()
        val liabilities = sum("LIABILITY").negate()
        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F01.03",
            period = asOf,
            cells = listOf(
                FinrepCell("r0010", "c0010", capital),
                FinrepCell("r0020", "c0010", capital),
                FinrepCell("r0040", "c0010", sharePremium),
                FinrepCell("r0070", "c0010", otherInstruments),
                FinrepCell("r0190", "c0010", retainedEarnings),
                FinrepCell("r0210", "c0010", otherReserves),
                FinrepCell("r0250", "c0010", currentProfit),
                FinrepCell("r0300", "c0010", equity),
                FinrepCell("r0310", "c0010", equity.add(liabilities)),
            ),
            dataGaps = FinrepCoverage.gapsFor("F01.03", snapshot.lines),
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }

    private fun creditBalance(lines: List<TrialBalanceLineDto>, vararg codes: String): BigDecimal = lines
        .filter { it.code in codes }
        .fold(BigDecimal.ZERO) { total, line -> total.subtract(line.net) }

    private fun debitBalance(lines: List<TrialBalanceLineDto>, vararg codes: String): BigDecimal = lines
        .filter { it.code in codes }
        .fold(BigDecimal.ZERO) { total, line -> total.add(line.net) }
}
