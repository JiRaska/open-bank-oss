// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.YearEndResultAllocationPlan
import java.util.UUID

/** Allocate a fiscal year's result to retained earnings; [requestedBy] is the verified JWT subject. */
data class AllocateYearEndResultCommand(val fiscalYear: Int, val requestedBy: String)

/** Read-only preview of what [YearEndResultAllocationUseCase.allocate] would post now. */
data class PreviewYearEndResultQuery(val fiscalYear: Int)

/**
 * The outcome of an allocation. [posted] is false when the plan was empty — the year's income and
 * expense accounts already net to zero, which is what a re-run after a successful allocation sees.
 */
data class YearEndResultAllocationResult(
    val plan: YearEndResultAllocationPlan,
    val posted: Boolean,
    val journalId: UUID?,
)

/** ADR-0096 D3: the year-end result allocation (P&L → retained earnings) closing journal. */
interface YearEndResultAllocationUseCase {
    suspend fun preview(query: PreviewYearEndResultQuery): YearEndResultAllocationPlan
    suspend fun allocate(command: AllocateYearEndResultCommand): YearEndResultAllocationResult
}
