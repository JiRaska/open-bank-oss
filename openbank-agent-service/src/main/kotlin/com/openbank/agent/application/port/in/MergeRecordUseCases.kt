// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.`in`

import com.openbank.agent.application.port.out.AgentMergeRecordRepository.InsertResult
import com.openbank.agent.domain.mergerecord.AgentMergeRecord
import com.openbank.agent.domain.mergerecord.MergeActorType
import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeAuthorizationKind
import java.time.Instant
import java.util.UUID

/**
 * Inbound port: record the merge executor and separate authorization (ADR-0031 phase 3).
 * Called by the ROLE_MERGE_RECORDER ingest endpoint only — a GitHub Actions workflow delivers merges here with a service
 * credential; agents themselves never record merges.
 */
interface RecordMergeUseCase {

    /**
     * Persist the merge record. Idempotent by [MergeRecordCommand.prNumber]: a redelivered webhook
     * returns the already-stored row with `inserted = false` and emits NO audit event — audit
     * exactly once, on the actual insert.
     */
    fun record(command: MergeRecordCommand): InsertResult
}

/** Inbound port: read side of the recorded-merge evidence, for the admin-ui BFF and oversight. */
interface MergeRecordQueries {

    /** Recorded merges newest first; [agentId] scopes to one agent. */
    fun list(agentId: String?): List<AgentMergeRecord>

    fun findByPrNumber(prNumber: Long): AgentMergeRecord?
}

/**
 * The validated ingest inputs. The service assigns [id] and [recordedAt] itself — callers supply
 * evidence, not identity — so the resource does not construct the aggregate.
 */
data class MergeRecordCommand(
    val agentId: String,
    val prNumber: Long,
    val prUrl: String,
    val headBranch: String,
    val mergedBy: String,
    val mergedAt: Instant,
    val title: String,
    val proposalId: UUID?,
    val source: String,
    val mergedByType: MergeActorType = MergeActorType.HUMAN,
    val mergedById: Long? = null,
    val headSha: String? = null,
    val mergeCommitSha: String? = null,
    val authorization: MergeAuthorization? = null,
) {
    init {
        require(prNumber > 0) { "prNumber must be positive" }
        when (mergedByType) {
            MergeActorType.HUMAN -> {
                require(mergedBy.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) {
                    "mergedBy must be a human GitHub login"
                }
                require(authorization == null) { "authorization is only valid for a GitHub App merge" }
            }
            MergeActorType.GITHUB_APP -> {
                require(mergedBy == "openbank-gitops-bot[bot]") { "untrusted merge executor" }
                require(mergedById != null && mergedById > 0) { "mergedById must be positive" }
                require(headSha?.matches(Regex("[0-9a-f]{40}")) == true) { "headSha must be a full lowercase SHA" }
                require(mergeCommitSha?.matches(Regex("[0-9a-f]{40}")) == true) {
                    "mergeCommitSha must be a full lowercase SHA"
                }
                requireNotNull(authorization) { "authorization is required for a GitHub App merge" }
                require(!authorization.approvedAt.isAfter(mergedAt)) { "authorization must precede the merge" }
                if (authorization.kind == MergeAuthorizationKind.POLICY_PR_AUTO_MERGE) {
                    require(!headBranch.startsWith("codex/")) { "codex branches require exact-head authorization" }
                    require(authorization.evidenceUrl == "https://github.com/JiRaska/open-bank-oss/pull/$prNumber") {
                        "policy evidenceUrl must identify this PR"
                    }
                    require(!requireNotNull(authorization.observedAt).isBefore(mergedAt)) {
                        "policy evidence cannot be observed before the merge"
                    }
                    require(
                        java.time.Duration.between(mergedAt, authorization.mergeEventAt).abs() <=
                            MERGE_EVENT_TOLERANCE,
                    ) { "policy merge event must match the canonical merge time" }
                }
                if (authorization.kind == MergeAuthorizationKind.LEGACY_PR_AUTO_MERGE) {
                    require(!mergedAt.isAfter(Instant.parse("2026-10-23T19:42:17Z"))) {
                        "legacy PR-scoped evidence is restricted to merges before exact-head approval governance"
                    }
                    require(authorization.evidenceUrl == "https://github.com/JiRaska/open-bank-oss/pull/$prNumber") {
                        "legacy evidenceUrl must identify this PR"
                    }
                    require(!requireNotNull(authorization.observedAt).isBefore(mergedAt)) {
                        "legacy evidence cannot be observed before the merge"
                    }
                }
            }
        }
    }
    private companion object {
        val MERGE_EVENT_TOLERANCE: java.time.Duration = java.time.Duration.ofSeconds(60)
    }
}
