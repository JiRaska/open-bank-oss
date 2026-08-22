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
    val budgetTokens: Int,
    val budgetContributions: Int,
    val deliveryMode: String = "HITL",
)

/** Raw `case_contribution` row (V1 + V3 columns). */
data class ContributionRow(
    val contributionId: String,
    val agentId: String,
    val contributedAtEpochMs: Long,
    val summary: String?,
    val evidenceRefs: List<String>,
    val draftVersion: Int?,
    val superseded: Boolean,
    val contested: Boolean,
    val tokensUsed: Int,
)

/** Raw terminal proposal row projected from `case_outbox` (ADR-0244 D7). */
data class ProposalEventRow(
    val proposalId: String,
    val proposalType: String,
    val status: String,
    val emittedAtEpochMs: Long,
)

data class CaseSignalEvidenceRow(
    val signalId: String,
    val agentId: String,
    val capability: String,
    val stage: String,
    val observedAtEpochMs: Long,
    val rolloutId: String?,
    val policyDecisionId: String?,
    val policyReason: String?,
)

enum class RuntimeEvidenceStage {
    AUTHORIZED,
    DENIED,
    INVOKED,
    CONSUMED,
    RECORDED,
    PERSISTED,
    EMITTED,
    PUBLISHED_TO_BROKER,
    PUBLISH_FAILED,
    SHADOW_RECORDED,
}

/** Runtime observation backing one rendered case edge. A charter never creates this object. */
data class RuntimeEvidence(
    val evidenceId: String,
    val source: String,
    val stage: RuntimeEvidenceStage,
    val observedAtEpochMs: Long,
    val correlationId: String,
    val detail: String,
)

enum class ThreadEntryType {
    CASE_OPENED,
    CONTRIBUTION,
    PROPOSAL_EMITTED,
    SHADOW_RECORDED,
    POLICY_DECISION,
    SIGNAL_INVOKED,
    SIGNAL_CONSUMED,
    CONTRIBUTION_PERSISTED,
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
    val shadow: Boolean = false,
    val runtimeEvidence: RuntimeEvidence,
    val tokensUsed: Int? = null,
    val signalId: String? = null,
    val capability: String? = null,
    val rolloutId: String? = null,
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
    val budgetTokens: Int,
    val budgetContributions: Int,
    val observedAtEpochMs: Long,
    val dataFromEpochMs: Long,
    val dataToEpochMs: Long,
    val lastSuccessfulLoadEpochMs: Long,
    val coverageStatus: String,
    val historySource: String,
    val retentionPolicy: String,
    val entries: List<ThreadEntry>,
)
