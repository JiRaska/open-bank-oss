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
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import java.time.Clock

/**
 * The recorded-human-merge lifecycle (ADR-0031 phase 3). `agent.proposal.decided` records that a
 * human decided a proposal; this records that a human MERGED the agent's PR — the merge half of
 * the AI-attribution evidence chain. Audited under the merging human (`HUMAN`, actorId =
 * [mergedBy]) with operation `agent.pr.merged`, via the same durable outbox as ProposalService, so
 * the event reaches the same Kafka pipeline.
 *
 * Idempotency is load-bearing for the audit trail, not just for storage: the GitHub webhook can
 * redeliver, and the audit event must fire exactly once. The repository returns whether the row
 * was actually inserted; the audit publishes only when it was.
 */
@ApplicationScoped
class MergeRecordService(
    private val repository: AgentMergeRecordRepository,
    private val auditPublisher: AuditEventPublisher,
    private val clock: Clock,
) : RecordMergeUseCase,
    MergeRecordQueries {

    override fun record(command: MergeRecordCommand): AgentMergeRecordRepository.InsertResult {
        val record = AgentMergeRecord(
            id = Ids.newId(),
            agentId = command.agentId,
            prNumber = command.prNumber,
            prUrl = command.prUrl,
            headBranch = command.headBranch,
            mergedBy = command.mergedBy,
            mergedAt = command.mergedAt,
            title = command.title,
            proposalId = command.proposalId,
            recordedAt = clock.instant(),
            source = command.source,
        )
        val outcome = repository.insertOrGetExisting(record)
        if (outcome.inserted) {
            runBlocking {
                auditPublisher.publish(
                    AuditEvent(
                        actorId = command.mergedBy,
                        actorType = "HUMAN",
                        operation = "agent.pr.merged",
                        resourceType = "agent.merge_record",
                        resourceId = record.id.toString(),
                        result = AuditResult.SUCCESS,
                        // Merge evidence only — no PR body or diff content (may quote PII).
                        payload = buildMap {
                            put("pr_number", command.prNumber)
                            put("head_branch", command.headBranch)
                            put("agent_id", command.agentId)
                            put("merged_at", command.mergedAt.toString())
                            put("source", command.source)
                        },
                    ),
                )
            }
        }
        return outcome
    }

    override fun list(agentId: String?): List<AgentMergeRecord> = repository.list(agentId)
}
