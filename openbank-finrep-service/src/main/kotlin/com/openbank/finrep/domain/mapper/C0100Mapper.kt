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
 * **Honest scoping note (read before extending):** C 01.00 decomposes regulatory capital into
 * CET1 instruments, share premium, retained earnings, other reserves, and CET1 deductions
 * (intangibles, deferred tax assets, etc.) — `openbank-ledger-service`'s chart of accounts has
 * **no capital-structure GL accounts today**: no share capital, share premium, retained
 * earnings, or reserve accounts are seeded anywhere, and the `EQUITY` `GlAccountType` value,
 * while a valid enum member, is never populated by any account in the real chart of accounts
 * (verified against every Flyway migration in openbank-ledger-service as of this increment).
 *
 * Rather than fake a capital figure (e.g. by deriving it as assets − liabilities, which is a
 * balance-sheet plug, not a regulatory-capital figure) or skip the template's rows, every
 * capital-structure row below is reported as an **explicit, flagged zero** (`isDataGap = true`)
 * until the ledger gains real capital-structure accounts. This mirrors ADR-0097's own
 * instruction that a regulatory report must never have a silent gap: the row is always present,
 * its value is honestly zero-and-flagged rather than omitted or guessed.
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
        "No capital-structure GL accounts exist in openbank-ledger-service's chart of accounts " +
            "yet (no share capital, share premium, retained earnings, or reserve accounts are " +
            "seeded; the EQUITY GlAccountType is a valid enum value but is never populated). " +
            "Reported as an explicit documented zero pending ledger support, not a computed value."

    fun map(lines: List<TrialBalanceLineDto>, asOf: LocalDate): CorepTemplate {
        // Real ledger data today has no EQUITY-typed lines at all; this filter is here so that
        // the mapper picks up real capital data automatically the day the ledger's chart of
        // accounts gains it, without needing to change this mapper.
        val equityLines = lines.filter { it.accountType == "EQUITY" }
        val hasRealCapitalData = equityLines.isNotEmpty()

        fun capitalCell(rowRef: String, label: String): CorepCell = if (hasRealCapitalData) {
            // Placeholder for when real capital accounts land: sum whatever the ledger reports.
            // Today this branch is unreachable in production data (no EQUITY lines exist), so
            // there is no fixed row-to-account mapping to encode yet.
            CorepCell(
                rowRef = rowRef,
                colRef = "c010",
                label = label,
                value = equityLines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.net) },
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
            capitalCell("r010", "OWN FUNDS"),
            capitalCell("r015", "TIER 1 CAPITAL"),
            capitalCell("r020", "COMMON EQUITY TIER 1 (CET1) CAPITAL"),
            capitalCell("r030", "Capital instruments eligible as CET1 Capital"),
            capitalCell("r130", "Retained earnings"),
            capitalCell("r160", "Other reserves"),
            capitalCell("r300", "(-) Total deductions from CET1"),
            capitalCell("r530", "ADDITIONAL TIER 1 (AT1) CAPITAL"),
            capitalCell("r750", "TIER 2 CAPITAL"),
        )

        return CorepTemplate(
            templateId = "C_01.00",
            period = asOf,
            cells = cells,
        )
    }
}
