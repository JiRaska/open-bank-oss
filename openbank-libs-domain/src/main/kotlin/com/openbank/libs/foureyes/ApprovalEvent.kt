// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.foureyes

import java.time.Instant
import java.util.UUID

/**
 * Domain events emitted by the four-eyes workflow on each state transition.
 *
 * These are the events that a downstream read-model (e.g. onboarding-service) or audit
 * log consumes to track approval-queue state. Each variant maps 1-to-1 to a [Proposal]
 * lifecycle method:
 *
 * ```
 * propose  → ApprovalProposed
 * approve  → ApprovalApproved
 * reject   → ApprovalRejected
 * withdraw → ApprovalWithdrawn
 * execute  → ApprovalExecuted   (the real mutation fired; final)
 * ```
 *
 * Events are serialised as JSON and emitted via an outbox row in the same transaction
 * as the state change. The owning service's [ApprovalEventPublisher] relays them to the
 * broker. All variants share [proposalId], [operation], [resourceType], [resourceId], and
 * [occurredAt] so a consumer can filter by operation/resource without deserialising the
 * full union.
 */
sealed class ApprovalEvent {
    abstract val proposalId: UUID
    abstract val operation: String
    abstract val resourceType: String
    abstract val resourceId: String
    abstract val occurredAt: Instant

    data class ApprovalProposed(
        override val proposalId: UUID,
        override val operation: String,
        override val resourceType: String,
        override val resourceId: String,
        val proposedBy: String,
        val payload: String,
        val ttlExpiry: Instant?,
        override val occurredAt: Instant,
    ) : ApprovalEvent()

    data class ApprovalApproved(
        override val proposalId: UUID,
        override val operation: String,
        override val resourceType: String,
        override val resourceId: String,
        val approvedBy: String,
        val reason: String?,
        override val occurredAt: Instant,
    ) : ApprovalEvent()

    data class ApprovalRejected(
        override val proposalId: UUID,
        override val operation: String,
        override val resourceType: String,
        override val resourceId: String,
        val rejectedBy: String,
        val reason: String?,
        override val occurredAt: Instant,
    ) : ApprovalEvent()

    data class ApprovalWithdrawn(
        override val proposalId: UUID,
        override val operation: String,
        override val resourceType: String,
        override val resourceId: String,
        val withdrawnBy: String,
        override val occurredAt: Instant,
    ) : ApprovalEvent()

    data class ApprovalExecuted(
        override val proposalId: UUID,
        override val operation: String,
        override val resourceType: String,
        override val resourceId: String,
        override val occurredAt: Instant,
    ) : ApprovalEvent()
}
