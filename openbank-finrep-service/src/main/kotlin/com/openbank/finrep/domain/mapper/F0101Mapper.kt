// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import com.openbank.finrep.domain.model.TrialBalanceIdentity
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps ledger trial balance lines to the FINREP F01.01 Balance Sheet template.
 *
 * EBA FINREP F01.01 structure (simplified):
 *   r010_c010 — Total assets
 *   r380_c010 — Total liabilities
 *   r490_c010 — Total equity
 *
 * ## Signs (issue #5987)
 *
 * Ledger publishes `net = totalDebit − totalCredit` for every account type, so a credit-normal
 * account arrives NEGATIVE. Every FINREP row is reported as a positive magnitude, hence the
 * negations below. The previous version summed the raw `net` straight into r380, which reported
 * liabilities as a negative number against real ledger data, and then derived r490 as
 * `assets − liabilities` — which for real data is `assets + |liabilities|`, not equity at all.
 *
 * ## Equity is SOURCED, not residual — and that is the whole point
 *
 * r490 is `−(equity + income − expense)` in ledger sign, i.e. contributed capital and reserves plus
 * the result of the period not yet closed out to reserves. It is read from the EQUITY, INCOME and
 * EXPENSE lines. It is deliberately NOT `assets − liabilities`: defining equity as the residual is
 * what made `isBalanced` unfalsifiable, because the identity then holds algebraically no matter
 * what the ledger contains. With equity sourced, [TrialBalanceIdentity] over the whole trial
 * balance is a claim that can be false — see the falsification pairs in `F0101MapperTest`.
 *
 * Note what today's chart of accounts means for this: openbank-ledger-service seeds **no EQUITY
 * accounts at all** (`V1__init_ledger.sql` and every later migration), so `Σ net(EQUITY)` is
 * genuinely zero and r490 currently reports the retained result alone. That is the honest number
 * for this ledger, and it does not weaken the check: the identity is evaluated over all five
 * account types, so a residual introduced anywhere still falsifies it.
 */
object F0101Mapper {

    fun map(lines: List<TrialBalanceLineDto>, asOf: LocalDate): FinrepTemplate {
        val assets = sumNet(lines, "ASSET")
        // Credit-normal, so the reporting magnitude is the negated ledger net.
        val liabilities = sumNet(lines, "LIABILITY").negate()
        val equity = sumNet(lines, "EQUITY", "INCOME", "EXPENSE").negate()

        val cells = listOf(
            FinrepCell(rowRef = "r010", colRef = "c010", value = assets),
            FinrepCell(rowRef = "r380", colRef = "c010", value = liabilities),
            FinrepCell(rowRef = "r490", colRef = "c010", value = equity),
        )

        return FinrepTemplate(
            templateId = "F01.01",
            period = asOf,
            cells = cells,
            isBalanced = TrialBalanceIdentity.holds(lines),
        )
    }

    private fun sumNet(lines: List<TrialBalanceLineDto>, vararg accountTypes: String): BigDecimal = lines
        .filter { it.accountType in accountTypes }
        .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }
}
