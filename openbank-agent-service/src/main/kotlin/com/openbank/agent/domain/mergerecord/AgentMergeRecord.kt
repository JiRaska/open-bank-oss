// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.domain.mergerecord

import java.time.Instant
import java.util.UUID

/**
 * ADR-0031 phase 3, "recorded human merge": the durable evidence that a HUMAN merged an
 * agent-authored PR. `agent.proposal.decided` records proposal decisions; nothing recorded WHO
 * merged the agent's PR — this is that record. Immutable, framework-free aggregate; storage lives
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
    /** The HUMAN login who pressed merge — the audit actor for this event. */
    val mergedBy: String,
    val mergedAt: Instant,
    val title: String,
    val proposalId: UUID?,
    val recordedAt: Instant,
    /** Ingest origin, e.g. "github-actions" — distinguishes pipeline-fed rows from manual backfills. */
    val source: String,
)
