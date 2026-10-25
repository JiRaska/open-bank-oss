// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application

import com.openbank.agent.application.port.`in`.MergeRecordCommand
import com.openbank.agent.application.port.`in`.MergeRecordQueries
import com.openbank.agent.application.port.`in`.RecordMergeUseCase
import com.openbank.agent.application.port.out.AgentMergeRecordRepository
import com.openbank.agent.domain.mergerecord.AgentMergeRecord
import com.openbank.agent.domain.mergerecord.MergeActorType
import com.openbank.agent.domain.mergerecord.MergeAuthorization
import com.openbank.agent.domain.mergerecord.MergeEvidenceConflict
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Records the actual merge executor independently from the human authorizer (ADR-0031).
 * A human merge remains HUMAN; an approved GitHub App merge emits SERVICE with the actual
 * App login. Separate authorization proof travels in the durable audit payload.
 *
 * Idempotency is load-bearing for the audit trail, not just for storage: the GitHub webhook can
 * redeliver, and the durable outbox must contain one event per recorded PR. Kafka delivery remains
 * at-least-once. The merge row and durable outbox enqueue share this method's transaction, so a
 * failed enqueue rolls back the row and redelivery retries the original insert-and-audit operation.
 */
@ApplicationScoped
class MergeRecordService(
    private val repository: AgentMergeRecordRepository,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) : RecordMergeUseCase,
    MergeRecordQueries {

    @Transactional(rollbackOn = [Exception::class])
    override fun record(command: MergeRecordCommand): AgentMergeRecordRepository.InsertResult {
        val observedAt = command.authorization?.observedAt
        require(
            observedAt == null || !observedAt.isAfter(clock.instant().plusSeconds(OBSERVATION_CLOCK_SKEW_SECONDS)),
        ) {
            "legacy evidence observation time is in the future"
        }
        val record = command.toRecord()
        val outcome = repository.insertOrGetExisting(record)
        if (!outcome.inserted) validateRetry(outcome.record, record)
        if (outcome.inserted) runBlocking { auditPublisher.publish(record.toAuditEvent()) }
        return outcome
    }

    private fun MergeRecordCommand.toRecord() = AgentMergeRecord(
        id = Ids.newId(),
        agentId = agentId,
        prNumber = prNumber,
        prUrl = prUrl,
        headBranch = headBranch,
        mergedBy = mergedBy,
        mergedAt = if (mergedByType == MergeActorType.GITHUB_APP) mergedAt.truncatedTo(ChronoUnit.MICROS) else mergedAt,
        title = title,
        proposalId = proposalId,
        recordedAt = clock.instant(),
        source = source,
        mergedByType = mergedByType,
        mergedById = mergedById,
        headSha = headSha,
        mergeCommitSha = mergeCommitSha,
        authorization = authorization?.toStoragePrecision(),
    )

    // PostgreSQL timestamptz has microsecond precision. Normalize before the initial response,
    // comparison, storage, and outbox publication so identical high-precision retries stay valid.
    private fun MergeAuthorization.toStoragePrecision() = copy(
        approvedAt = approvedAt.truncatedTo(ChronoUnit.MICROS),
        mergeEventAt = mergeEventAt?.truncatedTo(ChronoUnit.MICROS),
        observedAt = observedAt?.truncatedTo(ChronoUnit.MICROS),
    )

    private fun validateRetry(stored: AgentMergeRecord, incoming: AgentMergeRecord) {
        if (stored.mergedByType == MergeActorType.HUMAN && incoming.mergedByType == MergeActorType.HUMAN) return
        val sameExecutor = stored.mergedByType == incoming.mergedByType &&
            stored.mergedBy == incoming.mergedBy &&
            stored.mergedById == incoming.mergedById
        val sameSource = stored.headSha == incoming.headSha &&
            stored.headBranch == incoming.headBranch &&
            stored.agentId == incoming.agentId
        val sameMerge = stored.mergeCommitSha == incoming.mergeCommitSha &&
            stored.prUrl == incoming.prUrl &&
            stored.mergedAt == incoming.mergedAt
        val sameAuthorization = stored.authorization?.sameEvidenceAs(incoming.authorization) == true
        val sameRecord = sameExecutor && sameSource && sameMerge
        if (!sameRecord || !sameAuthorization) throw MergeEvidenceConflict()
    }

    private fun AgentMergeRecord.toAuditEvent() = AuditEvent(
        actorId = mergedBy,
        actorType = if (mergedByType == MergeActorType.HUMAN) "HUMAN" else "SERVICE",
        operation = "agent.pr.merged",
        resourceType = "agent.merge_record",
        resourceId = id.toString(),
        result = AuditResult.SUCCESS,
        // Merge evidence only — no PR body or diff content (may quote PII).
        payload = buildMap {
            put("pr_number", prNumber)
            put("head_branch", headBranch)
            put("agent_id", agentId)
            put("merged_at", mergedAt.toString())
            put("source", source)
            put("merged_by_type", mergedByType.name)
            mergedById?.let { put("merged_by_id", it) }
            headSha?.let { put("head_sha", it) }
            mergeCommitSha?.let { put("merge_commit_sha", it) }
            authorization?.let {
                put(
                    "authorization",
                    mapOf(
                        "approvedBy" to it.approvedBy,
                        "approvedById" to it.approvedById,
                        "approvedAt" to it.approvedAt.toString(),
                        "evidenceUrl" to it.evidenceUrl,
                        "statusId" to it.statusId,
                        "workflowRunId" to it.workflowRunId,
                        "kind" to it.kind.name,
                        "scope" to it.scope.name,
                        "pullRequestNodeId" to it.pullRequestNodeId,
                        "mergeEventNodeId" to it.mergeEventNodeId,
                        "mergeEventAt" to it.mergeEventAt?.toString(),
                        "observedAt" to it.observedAt?.toString(),
                        "policyCommitSha" to it.policyCommitSha,
                        "policyBlobSha" to it.policyBlobSha,
                    ),
                )
            }
        },
    )

    override fun findByPrNumber(prNumber: Long): AgentMergeRecord? = repository.findByPrNumber(prNumber)

    override fun list(agentId: String?): List<AgentMergeRecord> = repository.list(agentId)

    private companion object {
        // Allow bounded recorder/server clock skew, never unbounded future audit observations.
        const val OBSERVATION_CLOCK_SKEW_SECONDS = 60L
    }
}
