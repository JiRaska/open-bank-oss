// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.model.FinrepCell
import com.openbank.finrep.domain.model.FinrepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps ledger trial balance lines to the FINREP F01.01 Balance Sheet template.
 *
 * EBA FINREP F01.01 structure (simplified):
 *   r010_c010 — Total assets
 *   r380_c010 — Total liabilities
 *   r490_c010 — Total equity (assets − liabilities as derived net)
 */
object F0101Mapper {

    fun map(lines: List<TrialBalanceLineDto>, asOf: LocalDate): FinrepTemplate {
        val assets = lines
            .filter { it.accountType == "ASSET" }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }

        val liabilities = lines
            .filter { it.accountType == "LIABILITY" }
            .fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) }

        val equity = assets.subtract(liabilities)

        val cells = listOf(
            FinrepCell(rowRef = "r010", colRef = "c010", value = assets),
            FinrepCell(rowRef = "r380", colRef = "c010", value = liabilities),
            FinrepCell(rowRef = "r490", colRef = "c010", value = equity),
        )

        return FinrepTemplate(
            templateId = "F01.01",
            period = asOf,
            cells = cells,
            isBalanced = true,
        )
    }
}
