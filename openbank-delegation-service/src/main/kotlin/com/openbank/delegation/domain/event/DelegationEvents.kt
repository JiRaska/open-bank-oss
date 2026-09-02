// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Every event carries the full projection payload (resource, parties, capabilities):
 * a consuming product service builds its local enforcement projection (ADR-0232 D3)
 * from the event stream alone, never by calling back — the same reason ConsentRevoked
 * mirrors ConsentGranted's scopes.
 */
/**
 * The wire shape of a money amount on a delegation event.
 *
 * NOT [com.openbank.libs.domain.money.Money]: `CurrencyCode` is a data class with no `@JsonValue`,
 * so Jackson renders it as `{"code":"CZK"}` while both consumers read
 * `perTransactionLimit.currency` as TEXT — the amount would arrive and the currency would silently
 * be null. A flat `currency: String` matches the consumers and the fleet's own event convention
 * (AccountEvents, LedgerEvents both carry `currency: String`).
 */
data class EventMoney(val amount: BigDecimal, val currency: String) {
    companion object {
        fun from(money: Money?): EventMoney? = money?.let { EventMoney(it.amount, it.currency.code) }
    }
}

data class DelegationOffered(
    override val aggregateId: UUID,
    val lifecycleRevision: Long,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime? = null,
    val perTransactionLimit: EventMoney? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationOffered"
    override val version = 1L
}

data class DelegationActivated(
    override val aggregateId: UUID,
    val lifecycleRevision: Long,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime? = null,
    val perTransactionLimit: EventMoney? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationActivated"
    override val version = 1L
}

data class DelegationDeclined(
    override val aggregateId: UUID,
    val lifecycleRevision: Long,
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
    val lifecycleRevision: Long,
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
    val lifecycleRevision: Long,
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
    val lifecycleRevision: Long,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val capabilities: Set<DelegationCapability>,
    val validFrom: OffsetDateTime,
    val validTo: OffsetDateTime? = null,
    val perTransactionLimit: EventMoney? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "DelegationReinstated"
    override val version = 1L
}

data class DelegationRenounced(
    override val aggregateId: UUID,
    val lifecycleRevision: Long,
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
    val lifecycleRevision: Long,
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
