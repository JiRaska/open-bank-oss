// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.domain.mergerecord

import java.time.Instant
import java.util.UUID

/**
 * ADR-0031 phase 3: durable evidence of the actual merge executor and, for App merges,
 * the separate human authorization of an agent-authored PR. `agent.proposal.decided` records
 * proposal decisions; this record preserves the executor and authorization of the git merge.
 * Immutable, framework-free aggregate; storage lives
 * behind [com.openbank.agent.application.port.out.AgentMergeRecordRepository].
 *
 * [agentId] is the declared agent id (e.g. "flaky-test-hunter"), derived from the PR head branch
 * prefix/author by the caller — this aggregate trusts what it is given and stores it verbatim.
 * [proposalId] links back to the `agent_proposal` row when the caller can establish it (nullable —
 * a GitHub webhook knows PR numbers, not necessarily proposal ids).
 */
data class AgentMergeRecord(
    val id: UUID,
    val agentId: String,
    val prNumber: Long,
    val prUrl: String,
    val headBranch: String,
    /** Actual GitHub merge executor; authorization is recorded separately. */
    val mergedBy: String,
    val mergedAt: Instant,
    val title: String,
    val proposalId: UUID?,
    val recordedAt: Instant,
    /** Ingest origin, e.g. "github-actions" — distinguishes pipeline-fed rows from manual backfills. */
    val source: String,
    val mergedByType: MergeActorType = MergeActorType.HUMAN,
    val mergedById: Long? = null,
    val headSha: String? = null,
    val mergeCommitSha: String? = null,
    val authorization: MergeAuthorization? = null,
)

enum class MergeActorType { HUMAN, GITHUB_APP }

enum class MergeAuthorizationKind { OWNER_WORKFLOW_EXACT_HEAD, LEGACY_PR_AUTO_MERGE, POLICY_PR_AUTO_MERGE }

enum class MergeAuthorizationScope { HEAD, PR }

/**
 * Distinguishes exact-head approval from reconstruction of a historical PR-scoped auto-merge
 * request. Legacy evidence does not certify source-head approval or historical policy compliance.
 */
data class MergeAuthorization(
    val approvedBy: String,
    val approvedById: Long,
    val approvedAt: Instant,
    val evidenceUrl: String,
    val statusId: Long? = null,
    val workflowRunId: Long? = null,
    val kind: MergeAuthorizationKind = MergeAuthorizationKind.OWNER_WORKFLOW_EXACT_HEAD,
    val scope: MergeAuthorizationScope = if (kind == MergeAuthorizationKind.OWNER_WORKFLOW_EXACT_HEAD) {
        MergeAuthorizationScope.HEAD
    } else {
        MergeAuthorizationScope.PR
    },
    val pullRequestNodeId: String? = null,
    val mergeEventNodeId: String? = null,
    val mergeEventAt: Instant? = null,
    val observedAt: Instant? = null,
    val policyCommitSha: String? = null,
    val policyBlobSha: String? = null,
) {
    init {
        require(approvedBy.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) {
            "approvedBy must be a human GitHub login"
        }
        require(approvedById > 0) { "approvedById must be positive" }
        if (kind == MergeAuthorizationKind.POLICY_PR_AUTO_MERGE) {
            require(policyCommitSha?.matches(Regex("[0-9a-f]{40}")) == true) { "policyCommitSha must be a full SHA" }
            require(policyBlobSha?.matches(Regex("[0-9a-f]{40}")) == true) { "policyBlobSha must be a full SHA" }
        } else {
            require(policyCommitSha == null && policyBlobSha == null) { "policy context requires policy PR evidence" }
        }
        when (kind) {
            MergeAuthorizationKind.OWNER_WORKFLOW_EXACT_HEAD -> {
                require(scope == MergeAuthorizationScope.HEAD) { "exact-head authorization requires HEAD scope" }
                require(statusId != null && statusId > 0 && workflowRunId != null && workflowRunId > 0) {
                    "exact-head authorization ids must be positive"
                }
                require(evidenceUrl == "https://github.com/JiRaska/open-bank-oss/actions/runs/$workflowRunId") {
                    "authorization evidenceUrl must identify the trusted workflow run"
                }
                require(
                    pullRequestNodeId == null && mergeEventNodeId == null && mergeEventAt == null && observedAt == null,
                ) {
                    "exact-head authorization cannot carry legacy observation fields"
                }
            }
            MergeAuthorizationKind.LEGACY_PR_AUTO_MERGE, MergeAuthorizationKind.POLICY_PR_AUTO_MERGE -> {
                require(scope == MergeAuthorizationScope.PR) { "legacy authorization requires PR scope" }
                require(statusId == null && workflowRunId == null) { "legacy evidence is not workflow approval" }
                require(!pullRequestNodeId.isNullOrBlank() && !mergeEventNodeId.isNullOrBlank()) {
                    "legacy evidence requires GitHub PR and merge-event node ids"
                }
                require(mergeEventAt != null && observedAt != null) { "legacy evidence observation times are required" }
                require(!approvedAt.isAfter(mergeEventAt)) { "auto-merge request must precede the merge event" }
                require(!mergeEventAt.isAfter(observedAt)) { "legacy observation must follow the merge event" }
            }
        }
    }

    /** Observation refreshes do not change the identity of the historical authorization evidence. */
    fun sameEvidenceAs(other: MergeAuthorization?): Boolean {
        if (other == null || kind != other.kind) return false
        return if (scope == MergeAuthorizationScope.PR) {
            copy(observedAt = other.observedAt) == other
        } else {
            this == other
        }
    }
}

/** A duplicate cannot replace the original actor, head, or authorization evidence. */
class MergeEvidenceConflict : RuntimeException("merge evidence conflicts with the stored record")
