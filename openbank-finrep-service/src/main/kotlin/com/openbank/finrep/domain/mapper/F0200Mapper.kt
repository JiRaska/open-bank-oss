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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps ledger trial balance lines to the FINREP F02.00 Profit and Loss Statement template.
 *
 * EBA Reporting Framework 4.2 defines profit or loss for the year as r0670/c0010 (datapoint
 * 57025). The previous r0010/r0030/r0450 claims were interest income, a financial-asset detail and
 * other provisions respectively — not aggregate income, aggregate expense and net profit (#6980).
 *
 * Income is credit-normal, so its reporting magnitude is the negated ledger net; expense is
 * debit-normal and passes through. See `F0101Mapper` for the sign convention (issue #5987).
 *
 * ## What `isBalanced` means on a P&L, and why it is not `true` here
 *
 * F02.00 has no balance-sheet identity of its own — `net profit = income − expense` is a definition
 * and can never fail. So this template does NOT report a P&L-internal check. It reports the same
 * thing F01.01 does: whether the **trial balance it was rendered from** satisfies double entry. A
 * P&L drawn off a trial balance that does not tie out is not submittable regardless of how
 * internally consistent its derived profit fact is, and that is a fact about this render which a consumer
 * of this template can act on.
 *
 * The alternative the issue offers — modelling the flag as not-applicable to P&L — was rejected
 * because the flag would then have exactly one reachable value on this template, which is the
 * defect being fixed rather than a fix for it.
 */
object F0200Mapper {

    fun map(snapshot: TrialBalanceSnapshot, asOf: LocalDate): FinrepTemplate {
        val lines = snapshot.lines
        val income = sumNet(lines, "INCOME").negate()
        val expense = sumNet(lines, "EXPENSE")
        val netProfit = income.subtract(expense)
        val interestIncome = creditBalance(lines, "3000", "4100", "4101", "4102", "4103")
        val interestExpense = debitBalance(lines, "4000", "4010", "4011", "4012", "4013")
        val feeIncome = creditBalance(lines, "4001", "4003")
        val exchangeDifference = creditBalance(lines, "4002", "5900")
        val impairment = debitBalance(lines, "5100", "5101", "5102", "5103")

        val cells = listOf(
            FinrepCell("r0010", "c0010", interestIncome),
            FinrepCell("r0051", "c0010", interestIncome),
            FinrepCell("r0090", "c0010", interestExpense),
            FinrepCell("r0120", "c0010", interestExpense),
            FinrepCell("r0200", "c0010", feeIncome),
            FinrepCell("r0310", "c0010", exchangeDifference),
            FinrepCell("r0355", "c0010", income.subtract(interestExpense)),
            FinrepCell("r0460", "c0010", impairment),
            FinrepCell("r0491", "c0010", impairment),
            FinrepCell("r0610", "c0010", netProfit),
            FinrepCell("r0630", "c0010", netProfit),
            FinrepCell("r0670", "c0010", netProfit),
            FinrepCell("r0690", "c0010", netProfit),
        )

        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F02.00",
            period = asOf,
            cells = cells,
            dataGaps = FinrepCoverage.gapsFor("F02.00", lines),
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }

    private fun sumNet(lines: List<TrialBalanceLineDto>, accountType: String): BigDecimal = lines
        .filter { it.accountType == accountType }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }

    private fun creditBalance(lines: List<TrialBalanceLineDto>, vararg codes: String): BigDecimal = lines
        .filter { it.code in codes }
        .fold(BigDecimal.ZERO) { acc, line -> acc.subtract(line.net) }

    private fun debitBalance(lines: List<TrialBalanceLineDto>, vararg codes: String): BigDecimal = lines
        .filter { it.code in codes }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }
}
