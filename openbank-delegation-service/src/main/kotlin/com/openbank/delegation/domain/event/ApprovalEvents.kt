// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.ApprovalKind
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/*
 * ADR-0312 approval lifecycle events, published on `openbank.delegation.approval-events` through the
 * transactional outbox. Schema version 1 (DomainEvent.version); every type carries the SAME
 * fields so a consumer (notification-service) reads one shape. A breaking change ships as a new
 * version, never an in-place edit (ADR-0006). Documented in
 * openbank-contracts/openbank-delegation-service/asyncapi.yaml (`approvalEvent`).
 *
 * The consumer (notification-service, #10313) reads the flat v1 fields `type`, `approvalId`,
 * `entityName`, `initiatorName`, `amount`, `currency`, `payeeName`, `expiresAt`, `reason` and
 * `recipientPartyIds`; `eventType`/`aggregateId` carry the same values for the outbox and audit.
 *
 * `recipientPartyIds` is who should be told: for APPROVAL_REQUESTED every eligible signer except
 * those who already signed (so never the initiator of a payment); for terminal events the
 * initiator plus every eligible signer.
 */

private const val AGGREGATE = "ApprovalRequest"

/** Stated by the producer (audit attribution EVENT, not TOPIC); the publisher stamps the same value. */
private const val SOURCE_SERVICE = "delegation-service"

/** v1 of the consumer contract pinned with notification-service (#10313). */
const val SCHEMA_VERSION = 1

data class ApprovalRequested(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "APPROVAL_REQUESTED"
    }
}

data class ApprovalSigned(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "APPROVAL_SIGNED"
    }
}

data class ApprovalCompleted(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "APPROVAL_COMPLETED"
    }
}

data class ApprovalRejected(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "APPROVAL_REJECTED"
    }
}

data class ApprovalExpired(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "APPROVAL_EXPIRED"
    }
}

data class PaymentReleased(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PAYMENT_RELEASED"
    }
}

data class PaymentReleaseFailed(
    override val aggregateId: UUID,
    val entityPartyId: UUID,
    val kind: ApprovalKind,
    val status: ApprovalStatus,
    val requiredSignatures: Int,
    val collectedSignatures: Int,
    val initiatorPartyId: UUID,
    val actorPartyId: UUID?,
    val recipientPartyIds: List<UUID>,
    val signerPartyIds: List<UUID>,
    val expiresAt: Instant,
    val payloadSha256: String,
    val summary: Map<String, Any?>,
    val reason: String?,
    val releaseRef: String?,
    override val occurredAt: Instant,
    val approvalId: UUID,
    val type: String,
    val entityName: String?,
    val initiatorName: String?,
    val amount: String?,
    val currency: String?,
    val payeeName: String?,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PAYMENT_RELEASE_FAILED"
    }
}

/** Every event type this family publishes — the publisher routes these to the approval topic. */
val APPROVAL_EVENT_TYPES: Set<String> = setOf(
    ApprovalRequested.EVENT_TYPE,
    ApprovalSigned.EVENT_TYPE,
    ApprovalCompleted.EVENT_TYPE,
    ApprovalRejected.EVENT_TYPE,
    ApprovalExpired.EVENT_TYPE,
    PaymentReleased.EVENT_TYPE,
    PaymentReleaseFailed.EVENT_TYPE,
)
