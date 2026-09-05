// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.model

import java.time.LocalDate

/**
 * One rendered FINREP template (e.g. F01.01 Balance Sheet, F02.00 P&L).
 * Cells are the populated (row, column) coordinates for the reporting period.
 */
data class FinrepTemplate(
    val templateId: String,
    val period: LocalDate,
    val cells: List<FinrepCell>,
    /** Explicit findings that make this bounded preview ineligible as a regulatory artifact. */
    val dataGaps: List<FinrepDataGap>,
    /**
     * Whether this template may be treated as balanced — TRUE only when finrep's own recomputation
     * (`Σ net == 0` per currency, `TrialBalanceIdentity`) and openbank-ledger-service's published
     * verdict AGREE that it is, i.e. [balanceVerdict] is [BalanceVerdict.AGREED_BALANCED]
     * (issue #6011). Requiring both is what makes a truncated-but-internally-balanced response
     * visible: finrep's recomputation alone passes it.
     *
     * NO DEFAULT on purpose (issue #5987): the field spent its whole life as a hardcoded `true`
     * that no producer computed, and a default would let a new producer omit it and re-publish that
     * same constant silently.
     */
    val isBalanced: Boolean,
    /**
     * WHICH of the two sources objected, and whether they objected together (issue #6011).
     * [isBalanced] is deliberately a strict function of this field, never an independent boolean:
     * a plain imbalance, a disagreement between the two sources and a missing producer verdict are
     * three different defects with three different owners, and collapsing them onto one flag is the
     * `PushResult.skipped()` failure this repo already paid for once.
     */
    val balanceVerdict: BalanceVerdict,
) {
    val hasDataGaps: Boolean get() = dataGaps.isNotEmpty()
}
