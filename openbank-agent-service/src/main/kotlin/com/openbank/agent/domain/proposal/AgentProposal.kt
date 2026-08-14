// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.domain.proposal

import java.time.Instant
import java.util.UUID

/** PROPOSED → APPROVED | REJECTED. The agent only ever creates PROPOSED; a human decides. */
enum class ProposalState { PROPOSED, APPROVED, REJECTED }

/**
 * A reviewable proposal an agent materialised for a human to approve or reject (ADR-0031 D4:
 * agents propose, governance disposes). Immutable, framework-free: it is the aggregate the
 * lifecycle service reasons about, independent of how it is stored.
 *
 * Storage lives behind [com.openbank.agent.application.port.out.AgentProposalRepository]; the
 * only implementation today is plain Agroal JDBC, because this service depends on openbank-libs'
 * Hibernate *Reactive* Panache entities and the synchronous MCP `call()` path cannot drive them.
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
    /** Immutable, non-secret proposal provenance (for example a reviewed catalog snapshot hash). */
    val metadata: Map<String, String> = emptyMap(),
)
