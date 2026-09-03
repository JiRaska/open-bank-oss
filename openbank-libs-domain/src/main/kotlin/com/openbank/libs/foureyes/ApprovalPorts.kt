// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.foureyes

import com.openbank.libs.governance.ProposalState
import java.time.Instant
import java.util.UUID

/**
 * Port for persistence of [ApprovalEntry] records.
 *
 * Each service that uses four-eyes provides a Panache implementation backed by its own
 * approval-requests table (e.g. `kyc_approval_requests`). The table DDL is the service's
 * own Flyway migration; the column layout is documented by [PanacheApprovalRequestEntity].
 *
 * ### Expired rows
 * [findPendingActive] must exclude rows where `ttl_expiry IS NOT NULL AND ttl_expiry < now()`.
 * Expired rows are returned by [findByProposalId] so callers can surface "approval expired"
 * messages, but they are never surfaced as actionable pending items.
 */
interface ApprovalRepository {
    suspend fun save(entry: ApprovalEntry)
    suspend fun findByProposalId(proposalId: UUID): ApprovalEntry?

    /**
     * Active (non-expired) PROPOSED rows for [resourceType], oldest first.
     * Used to render the approval queue in the onboarding cockpit.
     */
    suspend fun findPendingActive(
        resourceType: String,
        limit: Int = 50,
        asOf: Instant,
    ): List<ApprovalEntry>

    /** All approval requests (any state) for a specific resource, newest first. */
    suspend fun findByResourceId(resourceType: String, resourceId: String): List<ApprovalEntry>

    /**
     * Persist the result of a state transition. The caller passes a fully updated [ApprovalEntry]
     * (produced by calling the corresponding [com.openbank.libs.governance.Proposal] method and
     * re-mapping). [updatedAt] is set by the caller at transition time.
     */
    suspend fun updateState(entry: ApprovalEntry)
}

/**
 * Port for emitting [ApprovalEvent]s to the broker.
 *
 * Implementations relay events written by the four-eyes workflow to whatever topic the
 * service has wired up (e.g. via a SmallRye `@Outgoing` channel). The event is serialised
 * as JSON; its Kafka headers are set by the transport following [OutboxKafkaHeaders] conventions
 * (aggregate id = proposalId as partition key; event id as idempotency key).
 */
interface ApprovalEventPublisher {
    suspend fun publish(event: ApprovalEvent)
}

/** Convenience: map a [ProposalState] to a displayable, stable string for API responses. */
fun ProposalState.label(): String = when (this) {
    ProposalState.PROPOSED -> "PENDING_APPROVAL"
    ProposalState.APPROVED -> "APPROVED"
    ProposalState.REJECTED -> "REJECTED"
    ProposalState.WITHDRAWN -> "WITHDRAWN"
    ProposalState.EXECUTED -> "EXECUTED"
}
