// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.foureyes

import com.openbank.libs.governance.Proposal
import com.openbank.libs.governance.ProposalState
import java.time.Instant
import java.util.UUID

/**
 * Immutable DTO representing a persisted approval request.
 *
 * Mirrors [Proposal] field-for-field but is decoupled from the pure domain type so callers
 * can work with DTOs across service/port boundaries without depending on [Proposal] directly.
 * The [payload] is the JSON-serialised action (opaque TEXT); libs does not depend on the
 * caller's action DTO type.
 *
 * [ttlExpiry] is optional. When set, the approval request is treated as expired once
 * [Instant.now] exceeds this value; [ApprovalRepository.findPendingActive] excludes expired rows.
 */
data class ApprovalEntry(
    val proposalId: UUID,
    val operation: String,
    val resourceType: String,
    val resourceId: String,
    val state: ProposalState,
    val proposedBy: String,
    val proposedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionReason: String?,
    val executedAt: Instant?,
    val payload: String,
    val ttlExpiry: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Reconstruct the pure [Proposal] domain type from this entry. Payload type is String. */
    fun toProposal(): Proposal<String> = Proposal(
        id = proposalId.toString(),
        action = payload,
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        state = state,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        executedAt = executedAt,
    )
}
