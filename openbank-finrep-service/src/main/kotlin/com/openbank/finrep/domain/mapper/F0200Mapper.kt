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
 * EBA FINREP F02.00 structure (simplified):
 *   r010_c010 — Total income
 *   r030_c010 — Total expense
 *   r450_c010 — Net profit (income − expense)
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
 * internally consistent its three rows are, and that is a fact about this render which a consumer
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

        val cells = listOf(
            FinrepCell(rowRef = "r010", colRef = "c010", value = income),
            FinrepCell(rowRef = "r030", colRef = "c010", value = expense),
            FinrepCell(rowRef = "r450", colRef = "c010", value = netProfit),
        )

        val assessment = TrialBalanceAssurance.assess(snapshot)
        return FinrepTemplate(
            templateId = "F02.00",
            period = asOf,
            cells = cells,
            isBalanced = assessment.verdict == BalanceVerdict.AGREED_BALANCED,
            balanceVerdict = assessment.verdict,
        )
    }

    private fun sumNet(lines: List<TrialBalanceLineDto>, accountType: String): BigDecimal = lines
        .filter { it.accountType == accountType }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }
}
