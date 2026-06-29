// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.domain

import java.time.Instant
import java.util.UUID

/**
 * Durable handle for a money-path action proposal (ADR-0089 D2, Track A).
 *
 * Issued when the model proposes an action that requires HITL + SCA confirmation.
 * The token is returned to the app as an opaque id; the app exchanges it at the
 * action-confirm endpoint (existing edge SCA flow). Domain class — no framework imports.
 */
data class ProposalToken(
    val id: UUID,
    val toolName: String,
    val params: Map<String, Any>,
    val expiresAt: Instant,
    val customerId: String,
)
