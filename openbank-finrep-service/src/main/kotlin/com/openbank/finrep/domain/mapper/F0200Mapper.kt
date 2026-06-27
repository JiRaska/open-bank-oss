// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps ledger trial balance lines to the FINREP F02.00 Profit and Loss Statement template.
 *
 * EBA FINREP F02.00 structure (simplified):
 *   r010_c010 — Total income
 *   r030_c010 — Total expense
 *   r450_c010 — Net profit (income − expense)
 */
object F0200Mapper {

    fun map(lines: List<TrialBalanceLineDto>, asOf: LocalDate): FinrepTemplate {
        val income = lines
            .filter { it.accountType == "INCOME" }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }

        val expense = lines
            .filter { it.accountType == "EXPENSE" }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }

        val netProfit = income.subtract(expense)

        val cells = listOf(
            FinrepCell(rowRef = "r010", colRef = "c010", value = income),
            FinrepCell(rowRef = "r030", colRef = "c010", value = expense),
            FinrepCell(rowRef = "r450", colRef = "c010", value = netProfit),
        )

        return FinrepTemplate(
            templateId = "F02.00",
            period = asOf,
            cells = cells,
            isBalanced = true,
        )
    }
}
