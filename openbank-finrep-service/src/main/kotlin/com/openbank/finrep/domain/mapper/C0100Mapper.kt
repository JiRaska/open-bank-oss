// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.TrialBalanceLineDto
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps ledger trial balance lines to the COREP C 01.00 Own Funds template (ADR-0097 Phase 2,
 * first increment).
 *
 * The mapper consumes explicit capital-structure GL accounts seeded by ledger migration V25.
 * It never derives own funds as assets minus liabilities: that would be an accounting residual,
 * not regulatory capital. A trial balance without any recognised capital source account remains
 * a visible data gap. Once the capital structure is present, an absent optional category is a
 * supported zero (for example a bank may issue no AT1 or Tier 2 instruments).
 *
 * EBA COREP C 01.00 structure (simplified, own-funds rows relevant to this first increment):
 *   r010_c010 — OWN FUNDS (total)
 *   r015_c010 — TIER 1 CAPITAL
 *   r020_c010 — COMMON EQUITY TIER 1 (CET1) CAPITAL
 *   r030_c010 — Capital instruments eligible as CET1 Capital
 *   r130_c010 — Retained earnings
 *   r160_c010 — Other reserves
 *   r300_c010 — (-) Total deductions from CET1
 *   r530_c010 — ADDITIONAL TIER 1 (AT1) CAPITAL
 *   r750_c010 — TIER 2 CAPITAL
 *
 * XBRL/DPM taxonomy output and the ČNB transmission channel are NOT built in this increment —
 * see the ADR-0097 delivery note.
 */
object C0100Mapper {

    private const val GAP_REASON =
        "The trial balance contains no recognised regulatory-capital source account " +
            "(6000-6060). No own-funds value was inferred from assets minus liabilities."

    private val capitalCodes = setOf("6000", "6010", "6020", "6030", "6040", "6050", "6060")

    fun map(lines: List<TrialBalanceLineDto>, asOf: LocalDate): CorepTemplate {
        val capitalLines = lines.filter { it.accountType == "EQUITY" && it.code in capitalCodes }
        val hasRealCapitalData = capitalLines.isNotEmpty()

        fun creditBalance(vararg codes: String): BigDecimal = capitalLines
            .filter { it.code in codes }
            .fold(BigDecimal.ZERO) { acc, line -> acc.subtract(line.net) }

        fun debitBalance(vararg codes: String): BigDecimal = capitalLines
            .filter { it.code in codes }
            .fold(BigDecimal.ZERO) { acc, line -> acc.add(line.net) }

        val instruments = creditBalance("6000", "6010")
        val retainedEarnings = creditBalance("6020")
        val otherReserves = creditBalance("6030")
        val deductions = debitBalance("6040")
        val cet1 = instruments.add(retainedEarnings).add(otherReserves).subtract(deductions)
        val at1 = creditBalance("6050")
        val tier1 = cet1.add(at1)
        val tier2 = creditBalance("6060")
        val ownFunds = tier1.add(tier2)

        fun capitalCell(rowRef: String, label: String, value: BigDecimal): CorepCell = if (hasRealCapitalData) {
            CorepCell(
                rowRef = rowRef,
                colRef = "c010",
                label = label,
                value = value,
                isDataGap = false,
            )
        } else {
            CorepCell(
                rowRef = rowRef,
                colRef = "c010",
                label = label,
                value = BigDecimal.ZERO,
                isDataGap = true,
                gapReason = GAP_REASON,
            )
        }

        val cells = listOf(
            capitalCell("r010", "OWN FUNDS", ownFunds),
            capitalCell("r015", "TIER 1 CAPITAL", tier1),
            capitalCell("r020", "COMMON EQUITY TIER 1 (CET1) CAPITAL", cet1),
            capitalCell("r030", "Capital instruments eligible as CET1 Capital", instruments),
            capitalCell("r130", "Retained earnings", retainedEarnings),
            capitalCell("r160", "Other reserves", otherReserves),
            capitalCell("r300", "(-) Total deductions from CET1", deductions),
            capitalCell("r530", "ADDITIONAL TIER 1 (AT1) CAPITAL", at1),
            capitalCell("r750", "TIER 2 CAPITAL", tier2),
        )

        return CorepTemplate(
            templateId = "C_01.00",
            period = asOf,
            cells = cells,
        )
    }
}
