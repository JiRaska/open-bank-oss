// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.rest

import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeAuthorizationKind
import com.openbank.agent.domain.mergerecord.MergeAuthorizationScope
import org.eclipse.microprofile.openapi.annotations.media.Schema
import java.time.Instant

/** Keep the existing closed wire enum stable; a new proof uses a separate additive slot. */
enum class ExistingAuthorizationKind { OWNER_WORKFLOW_EXACT_HEAD, LEGACY_PR_AUTO_MERGE }

enum class PolicyAuthorizationKind { POLICY_PR_AUTO_MERGE }

@Schema(name = "MergeAuthorization")
data class ExistingMergeAuthorization(
    val approvedBy: String,
    val approvedById: Long,
    val approvedAt: Instant,
    val evidenceUrl: String,
    val statusId: Long? = null,
    val workflowRunId: Long? = null,
    val kind: ExistingAuthorizationKind = ExistingAuthorizationKind.OWNER_WORKFLOW_EXACT_HEAD,
    val scope: MergeAuthorizationScope = if (kind == ExistingAuthorizationKind.OWNER_WORKFLOW_EXACT_HEAD) {
        MergeAuthorizationScope.HEAD
    } else {
        MergeAuthorizationScope.PR
    },
    val pullRequestNodeId: String? = null,
    val mergeEventNodeId: String? = null,
    val mergeEventAt: Instant? = null,
    val observedAt: Instant? = null,
) {
    fun toDomain() = MergeAuthorization(
        approvedBy = approvedBy, approvedById = approvedById, approvedAt = approvedAt,
        evidenceUrl = evidenceUrl, statusId = statusId, workflowRunId = workflowRunId,
        kind = MergeAuthorizationKind.valueOf(kind.name), scope = scope,
        pullRequestNodeId = pullRequestNodeId, mergeEventNodeId = mergeEventNodeId,
        mergeEventAt = mergeEventAt, observedAt = observedAt,
    )
}

/** Pre-merge policy context and PR intent; never exact-head approval or full policy compliance. */
data class PolicyMergeAuthorization(
    val approvedBy: String,
    val approvedById: Long,
    val approvedAt: Instant,
    val evidenceUrl: String,
    val statusId: Long? = null,
    val workflowRunId: Long? = null,
    val kind: PolicyAuthorizationKind = PolicyAuthorizationKind.POLICY_PR_AUTO_MERGE,
    val scope: MergeAuthorizationScope = MergeAuthorizationScope.PR,
    val pullRequestNodeId: String? = null,
    val mergeEventNodeId: String? = null,
    val mergeEventAt: Instant? = null,
    val observedAt: Instant? = null,
    val policyCommitSha: String,
    val policyBlobSha: String,
) {
    fun toDomain() = MergeAuthorization(
        approvedBy = approvedBy, approvedById = approvedById, approvedAt = approvedAt,
        evidenceUrl = evidenceUrl, statusId = statusId, workflowRunId = workflowRunId,
        kind = MergeAuthorizationKind.valueOf(kind.name), scope = scope,
        pullRequestNodeId = pullRequestNodeId, mergeEventNodeId = mergeEventNodeId,
        mergeEventAt = mergeEventAt, observedAt = observedAt,
        policyCommitSha = policyCommitSha, policyBlobSha = policyBlobSha,
    )
}

fun MergeAuthorization.toExistingWire() = ExistingMergeAuthorization(
    approvedBy = approvedBy, approvedById = approvedById, approvedAt = approvedAt,
    evidenceUrl = evidenceUrl, statusId = statusId, workflowRunId = workflowRunId,
    kind = ExistingAuthorizationKind.valueOf(kind.name), scope = scope,
    pullRequestNodeId = pullRequestNodeId, mergeEventNodeId = mergeEventNodeId,
    mergeEventAt = mergeEventAt, observedAt = observedAt,
)

fun MergeAuthorization.toPolicyWire() = PolicyMergeAuthorization(
    approvedBy = approvedBy, approvedById = approvedById, approvedAt = approvedAt,
    evidenceUrl = evidenceUrl, statusId = statusId, workflowRunId = workflowRunId,
    kind = PolicyAuthorizationKind.POLICY_PR_AUTO_MERGE, scope = scope,
    pullRequestNodeId = pullRequestNodeId, mergeEventNodeId = mergeEventNodeId,
    mergeEventAt = mergeEventAt, observedAt = observedAt,
    policyCommitSha = requireNotNull(policyCommitSha), policyBlobSha = requireNotNull(policyBlobSha),
)
