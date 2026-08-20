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
    /**
     * Whether the double-entry identity of the trial balance this template was rendered from holds
     * (`Σ net == 0` per currency, `TrialBalanceIdentity`). NO DEFAULT on purpose (issue #5987): the
     * field spent its whole life as a hardcoded `true` that no producer computed, and a default
     * would let a new producer omit it and re-publish that same constant silently.
     */
    val isBalanced: Boolean,
)
