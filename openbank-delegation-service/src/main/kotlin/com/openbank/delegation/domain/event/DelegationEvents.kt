// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Every event carries the full projection payload (resource, parties, capabilities):
 * a consuming product service builds its local enforcement projection (ADR-0232 D3)
 * from the event stream alone, never by calling back — the same reason ConsentRevoked
 * mirrors ConsentGranted's scopes.
 */
data class DelegationOffered(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationOffered"
    override val version = 1L
}

data class DelegationActivated(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationActivated"
    override val version = 1L
}

data class DelegationDeclined(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationDeclined"
    override val version = 1L
}

data class DelegationRevoked(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationRevoked"
    override val version = 1L
}

data class DelegationSuspended(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationSuspended"
    override val version = 1L
}

data class DelegationReinstated(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationReinstated"
    override val version = 1L
}

data class DelegationRenounced(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationRenounced"
    override val version = 1L
}

data class DelegationExpired(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationExpired"
    override val version = 1L
}
