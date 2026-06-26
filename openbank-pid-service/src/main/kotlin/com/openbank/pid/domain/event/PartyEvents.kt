// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.domain.event

import com.openbank.libs.domain.event.DomainEvent
import com.openbank.pid.domain.model.KycLevel
import com.openbank.pid.domain.model.OnboardingChannel
import com.openbank.pid.domain.model.PartyRole
import com.openbank.pid.domain.model.PartyStatus
import java.util.UUID

data class PartyCreatedEvent(
    override val aggregateId: UUID,
    val partyType: String,
    val verificationSource: String,
    /** Full name for downstream account onboarding / sanctions screening. */
    val givenName: String = "",
    val familyName: String = "",
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "PartyCreated"
    override val version = 1L
}

data class CaseCreatedEvent(
    override val aggregateId: UUID,
    val caseId: com.openbank.libs.domain.case.CaseId,
    val caseType: com.openbank.libs.domain.case.CaseType,
    val status: com.openbank.libs.domain.case.CaseStatus,
    val actor: String,
    val reasonCode: com.openbank.libs.domain.case.CaseReasonCode,
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "case.created"
    override val version = 1L
}

data class CaseTransitionedEvent(
    override val aggregateId: UUID,
    val caseId: com.openbank.libs.domain.case.CaseId,
    val caseType: com.openbank.libs.domain.case.CaseType,
    val fromStatus: com.openbank.libs.domain.case.CaseStatus,
    val toStatus: com.openbank.libs.domain.case.CaseStatus,
    val reasonCode: com.openbank.libs.domain.case.CaseReasonCode,
    val actor: String,
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "case.transitioned"
    override val version = 1L
}

data class CaseEvidenceLinkedEvent(
    override val aggregateId: UUID,
    val caseId: com.openbank.libs.domain.case.CaseId,
    val evidenceRef: String,
    val actor: String,
    val linkedAt: java.time.Instant,
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "case.evidence.linked"
    override val version = 1L
}

data class PartyVerifiedEvent(override val aggregateId: UUID, val verificationSource: String, val kycLevel: KycLevel) :
    DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "PartyVerified"
    override val version = 1L
}

data class PartyStatusChangedEvent(
    override val aggregateId: UUID,
    val previousStatus: PartyStatus,
    val newStatus: PartyStatus,
    val reason: String?,
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "PartyStatusChanged"
    override val version = 1L
}

data class RelationshipAddedEvent(
    override val aggregateId: UUID,
    val relationshipId: UUID,
    val role: PartyRole,
    val channel: OnboardingChannel,
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "RelationshipAdded"
    override val version = 1L
}

data class RelationshipTerminatedEvent(
    override val aggregateId: UUID,
    val relationshipId: UUID,
    val role: PartyRole,
    val reason: String?,
) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "RelationshipTerminated"
    override val version = 1L
}

data class KycLevelChangedEvent(override val aggregateId: UUID, val previousLevel: KycLevel, val newLevel: KycLevel) :
    DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "KycLevelChanged"
    override val version = 1L
}

data class AddressUpdatedFromRobEvent(override val aggregateId: UUID, val syncedAt: java.time.OffsetDateTime) :
    DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "AddressUpdatedFromRob"
    override val version = 1L
}

/**
 * A new external identifier (e.g. a second Keycloak sub) was linked to an existing party —
 * the identity-unification merge of ADR-0072 §5: the same human arriving through another
 * channel resolves to the same golden-record party instead of creating a duplicate.
 */
data class ExternalIdLinkedEvent(override val aggregateId: UUID, val externalIdType: String) : DomainEvent() {
    override val aggregateType = "Party"
    override val eventType = "ExternalIdLinked"
    override val version = 1L
}
