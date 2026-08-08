// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseRow
import com.openbank.casecoordinator.domain.model.CaseSummary
import com.openbank.casecoordinator.domain.model.CaseThread
import com.openbank.casecoordinator.domain.model.ContributionRow
import com.openbank.casecoordinator.domain.model.ProposalEventRow
import com.openbank.casecoordinator.domain.model.ThreadEntry
import com.openbank.casecoordinator.domain.model.ThreadEntryType

/**
 * Pure projection from persistence rows to the ADR-0246 thread view. Separated from the JDBC
 * repository so the thread semantics (ordering, entry shapes, honest emptiness) are unit-testable
 * without a database — the repository only maps ResultSets to rows.
 */
object CaseThreadProjection {

    fun toSummary(row: CaseRow): CaseSummary = CaseSummary(
        caseId = row.workflowId,
        caseClass = row.caseClass,
        dispositionTarget = row.dispositionTarget,
        status = row.status,
        openedAtEpochMs = row.openedAtEpochMs,
        deadlineAtEpochMs = row.deadlineAtEpochMs,
        contestedRate = row.contestedRate,
        contributionCount = row.contributionCount,
    )

    fun project(case: CaseRow, contributions: List<ContributionRow>, proposals: List<ProposalEventRow>): CaseThread {
        val entries = buildList {
            add(
                ThreadEntry(
                    type = ThreadEntryType.CASE_OPENED,
                    atEpochMs = case.openedAtEpochMs,
                    summary = case.dispositionTarget,
                ),
            )
            contributions.forEach { c ->
                add(
                    ThreadEntry(
                        type = ThreadEntryType.CONTRIBUTION,
                        atEpochMs = c.contributedAtEpochMs,
                        actor = c.agentId,
                        summary = c.summary,
                        evidenceRefs = c.evidenceRefs,
                        draftVersion = c.draftVersion,
                        superseded = c.superseded,
                        contested = c.contested,
                    ),
                )
            }
            proposals.forEach { p ->
                add(
                    ThreadEntry(
                        type = ThreadEntryType.PROPOSAL_EMITTED,
                        atEpochMs = p.emittedAtEpochMs,
                        proposalId = p.proposalId,
                        proposalType = p.proposalType,
                    ),
                )
            }
        }.sortedBy { it.atEpochMs }

        return CaseThread(
            caseId = case.workflowId,
            caseClass = case.caseClass,
            dispositionTarget = case.dispositionTarget,
            status = case.status,
            openedAtEpochMs = case.openedAtEpochMs,
            deadlineAtEpochMs = case.deadlineAtEpochMs,
            contestedRate = case.contestedRate,
            entries = entries,
        )
    }
}
