// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.foureyes

import com.openbank.libs.governance.ProposalState
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Instant
import java.util.UUID

/**
 * Active-record base for a service's approval-request table.
 *
 * A concrete entity requires only an `@Entity` + `@Table` declaration:
 *
 * ```kotlin
 * @Entity
 * @Table(name = "kyc_approval_requests")
 * class KycApprovalRequestEntity : PanacheApprovalRequestEntity()
 * ```
 *
 * The service then provides a Flyway migration that creates the table using the column
 * layout defined here. A reference DDL fragment (PostgreSQL):
 *
 * ```sql
 * CREATE TABLE kyc_approval_requests (
 *     id             BIGSERIAL      PRIMARY KEY,
 *     proposal_id    UUID           NOT NULL UNIQUE,
 *     operation      TEXT           NOT NULL,
 *     resource_type  TEXT           NOT NULL,
 *     resource_id    TEXT           NOT NULL,
 *     state          TEXT           NOT NULL,
 *     proposed_by    TEXT           NOT NULL,
 *     proposed_at    TIMESTAMPTZ    NOT NULL,
 *     decided_by     TEXT,
 *     decided_at     TIMESTAMPTZ,
 *     decision_reason TEXT,
 *     executed_at    TIMESTAMPTZ,
 *     payload        TEXT           NOT NULL,
 *     ttl_expiry     TIMESTAMPTZ,
 *     created_at     TIMESTAMPTZ    NOT NULL,
 *     updated_at     TIMESTAMPTZ    NOT NULL
 * );
 * CREATE INDEX ON kyc_approval_requests (resource_type, resource_id);
 * CREATE INDEX ON kyc_approval_requests (state, ttl_expiry) WHERE state = 'PROPOSED';
 * ```
 *
 * The [state] column stores the [ProposalState] name (TEXT, not a DB enum) to keep schema
 * migrations additive when new states are introduced.
 *
 * Follow the same CDI-proxying constraints documented in [AbstractOutboxDispatcher]: resilience
 * annotations (`@Retry`, `@CircuitBreaker`, etc.) must be on the concrete bean's methods.
 */
@MappedSuperclass
open class PanacheApprovalRequestEntity : PanacheEntity() {

    @Column(name = "proposal_id", nullable = false, unique = true)
    lateinit var proposalId: UUID

    @Column(name = "operation", nullable = false)
    lateinit var operation: String

    @Column(name = "resource_type", nullable = false)
    lateinit var resourceType: String

    @Column(name = "resource_id", nullable = false)
    lateinit var resourceId: String

    /** Stores [ProposalState.name] — TEXT, not a database enum (additive migration-safe). */
    @Column(name = "state", nullable = false)
    lateinit var state: String

    @Column(name = "proposed_by", nullable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    var decisionReason: String? = null

    @Column(name = "executed_at")
    var executedAt: Instant? = null

    /** JSON-serialised action payload — opaque to libs; callers own the schema. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    lateinit var payload: String

    /**
     * Optional auto-expiry timestamp. When non-null and in the past, the proposal is treated as
     * expired: it is excluded from [ApprovalRepository.findPendingActive] and the four-eyes
     * workflow must not accept a confirm/reject on it.
     */
    @Column(name = "ttl_expiry")
    var ttlExpiry: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    fun toEntry(): ApprovalEntry = ApprovalEntry(
        proposalId = proposalId,
        operation = operation,
        resourceType = resourceType,
        resourceId = resourceId,
        state = ProposalState.valueOf(state),
        proposedBy = proposedBy,
        proposedAt = proposedAt,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        executedAt = executedAt,
        payload = payload,
        ttlExpiry = ttlExpiry,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun applyEntry(entry: ApprovalEntry): PanacheApprovalRequestEntity {
        proposalId = entry.proposalId
        operation = entry.operation
        resourceType = entry.resourceType
        resourceId = entry.resourceId
        state = entry.state.name
        proposedBy = entry.proposedBy
        proposedAt = entry.proposedAt
        decidedBy = entry.decidedBy
        decidedAt = entry.decidedAt
        decisionReason = entry.decisionReason
        executedAt = entry.executedAt
        payload = entry.payload
        ttlExpiry = entry.ttlExpiry
        createdAt = entry.createdAt
        updatedAt = entry.updatedAt
        return this
    }
}
