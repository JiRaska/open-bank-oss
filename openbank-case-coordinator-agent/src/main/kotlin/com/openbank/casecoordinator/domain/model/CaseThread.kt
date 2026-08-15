// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.domain.model

/**
 * Read model for the ADR-0246 swarm thread view (Phase 2, #4185). Pure Kotlin (ADR-0002).
 * Rows are the raw persistence shape; the API types are what `GET /cases{,/{id}}` returns.
 */

/** Raw `case_workflow` row (V1 + V3 columns). */
data class CaseRow(
    val workflowId: String,
    val caseClass: String,
    val dispositionTarget: String,
    val status: String,
    val openedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val contestedRate: Double,
    val contributionCount: Int,
)

/** Raw `case_contribution` row (V1 + V3 columns). */
data class ContributionRow(
    val agentId: String,
    val contributedAtEpochMs: Long,
    val summary: String?,
    val evidenceRefs: List<String>,
    val draftVersion: Int?,
    val superseded: Boolean,
    val contested: Boolean,
)

/** Raw terminal proposal row projected from `case_outbox` (ADR-0244 D7). */
data class ProposalEventRow(val proposalId: String, val proposalType: String, val emittedAtEpochMs: Long)

enum class ThreadEntryType {
    CASE_OPENED,
    CONTRIBUTION,
    PROPOSAL_EMITTED,
}

/** One thread entry in the ADR-0246 timeline, oldest first. */
data class ThreadEntry(
    val type: ThreadEntryType,
    val atEpochMs: Long,
    val actor: String? = null,
    val summary: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val draftVersion: Int? = null,
    val superseded: Boolean = false,
    val contested: Boolean = false,
    val proposalId: String? = null,
    val proposalType: String? = null,
)

/** Case list item — `GET /api/v1/case-coordinator/cases`. */
data class CaseSummary(
    val caseId: String,
    val caseClass: String,
    val dispositionTarget: String,
    val status: String,
    val openedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val contestedRate: Double,
    val contributionCount: Int,
)

/** Case detail with the full thread — `GET /api/v1/case-coordinator/cases/{caseId}`. */
data class CaseThread(
    val caseId: String,
    val caseClass: String,
    val dispositionTarget: String,
    val status: String,
    val openedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val contestedRate: Double,
    val entries: List<ThreadEntry>,
)
