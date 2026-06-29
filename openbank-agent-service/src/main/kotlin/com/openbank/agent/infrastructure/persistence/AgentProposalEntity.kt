// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.persistence

import java.time.Instant
import java.util.UUID

/** PROPOSED → APPROVED | REJECTED. The agent only ever creates PROPOSED; a human decides. */
enum class ProposalState { PROPOSED, APPROVED, REJECTED }

/**
 * Plain immutable row for the agent_proposal table.
 *
 * Deliberately NOT a Hibernate/Panache entity: this service depends on openbank-libs, which ships
 * Hibernate *Reactive* Panache entities (outbox, four-eyes). Pulling in Hibernate ORM here makes the
 * ORM JpaJandexScavenger try to register those reactive entities and fail (reactive Panache is not
 * on this service's classpath). The synchronous MCP tool path also can't drive reactive Panache. So
 * the proposals store is plain Agroal JDBC (see ProposalService) — sync, immediate-consistency CRUD.
 */
data class AgentProposal(
    val id: UUID,
    val title: String,
    val rationale: String,
    val suggestedAction: String,
    val proposedBy: String,
    val proposedAt: Instant,
    val state: ProposalState,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val modelId: String?,
    val correlationId: String?,
)
