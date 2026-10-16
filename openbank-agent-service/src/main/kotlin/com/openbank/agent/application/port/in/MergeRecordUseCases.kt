// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.`in`

import com.openbank.agent.application.port.out.AgentMergeRecordRepository.InsertResult
import com.openbank.agent.domain.mergerecord.AgentMergeRecord
import java.time.Instant
import java.util.UUID

/**
 * Inbound port: record a human merge of an agent-authored PR (ADR-0031 phase 3). Called by the
 * ROLE_ADMIN ingest endpoint only — a GitHub Actions workflow delivers merges here with a service
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
)
