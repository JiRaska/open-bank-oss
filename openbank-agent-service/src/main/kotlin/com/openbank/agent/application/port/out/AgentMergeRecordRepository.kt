// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.application.port.out

import com.openbank.agent.domain.mergerecord.AgentMergeRecord

/**
 * Outbound port: system of record for recorded human merges (ADR-0031 phase 3, ADR-0002 hexagonal).
 * Plain Agroal JDBC like [AgentProposalRepository] — this service cannot drive reactive Panache
 * (see that port's KDoc), and ingest is a synchronous webhook path anyway.
 *
 * Insert is idempotent by `pr_number` because the GitHub webhook can redeliver: a duplicate
 * delivery must surface the already-stored row, not fail or overwrite it.
 */
interface AgentMergeRecordRepository {

    /**
     * Persist [record]; on a `pr_number` conflict (redelivery) return the existing row instead.
     * [InsertResult.inserted] distinguishes the two so the caller audits only genuine first inserts.
     */
    fun insertOrGetExisting(record: AgentMergeRecord): InsertResult

    /** Merge records newest first; [agentId] filters on `agent_id`. */
    fun list(agentId: String?): List<AgentMergeRecord>

    data class InsertResult(val record: AgentMergeRecord, val inserted: Boolean)
}
