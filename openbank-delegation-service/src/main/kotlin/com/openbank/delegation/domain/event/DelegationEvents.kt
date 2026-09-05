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

/*
 * ADR-0249 D4 — the reservation half of "every grant, reservation, confirmation and revocation is
 * an audit event" (issue #5728).
 *
 * Until these existed, the D3 reserve/confirm/release path moved real money on a delegated
 * authority and wrote nothing to the outbox at all, while this ADR was the compliance-facing
 * record claiming it did. Card issuance/revocation was the only audited half.
 *
 * **Why `aggregateId` is the GRANT and not the reservation.** `OutboxKafkaHeaders.partitionKey`
 * returns `aggregateId`, so keying on the grant puts a reserve and a revoke of the same grant on
 * one partition, in order. Keying on the reservation would let a consumer see spend against an
 * authority whose revocation it has not applied yet. The reservation keeps its own id in
 * [SpendReserved.reservationId] and friends, which is what identifies the unit of spend.
 *
 * **Why PascalCase and not SCREAMING_SNAKE_CASE.** These share one topic
 * (`openbank.delegation.events`) with the eight lifecycle events above, whose `eventType` is
 * PascalCase and whose only in-tree consumers (`CardDelegationEventConsumer`, the account-service
 * twin) match those strings literally. The SCREAMING_SNAKE spelling belongs to a different
 * producer and a different topic — `EdgeAuditPublisher`'s `openbank.customer.audit`, where
 * `CUSTOMER_DELEGATED_CARD_ISSUED` lives. Putting a second idiom into this stream would make the
 * topic's own convention unstateable.
 */

/**
 * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
 * (EVENT-sourced) attribution — issues #3994/#5256, where producers omitting it landed every
 * audit row as `"unknown"` with no error, because the consumer falls back to `?: "unknown"`.
 *
 * These are serialised data classes, so the wire key is this Kotlin property name and a grep for
 * a quoted `"sourceService"` finds nothing here; `SpendReservationEventsTest` asserts it off real
 * serialised JSON rather than off the property.
 */
internal const val DELEGATION_SOURCE_SERVICE = "delegation-service"

/** Headroom was taken under the grant's ceilings — before the money moves. */
data class SpendReserved(
    override val aggregateId: UUID,
    val reservationId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val amount: EventMoney,
    /**
     * The caller's key for the spend this reservation backs — the only link from the audit trail
     * to the payment that consumed the headroom. Opaque and caller-chosen; never a PII field.
     */
    val idempotencyKey: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "SpendReserved"
    override val version = 1L
    val sourceService: String = DELEGATION_SOURCE_SERVICE
}

/** The money moved: the headroom stays consumed. Terminal for this reservation. */
data class SpendConfirmed(
    override val aggregateId: UUID,
    val reservationId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val amount: EventMoney,
    val settledAt: OffsetDateTime,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "SpendConfirmed"
    override val version = 1L
    val sourceService: String = DELEGATION_SOURCE_SERVICE
}

/** The payment did not happen: the headroom comes back. Terminal for this reservation. */
data class SpendReleased(
    override val aggregateId: UUID,
    val reservationId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val amount: EventMoney,
    val settledAt: OffsetDateTime,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = "SpendReleased"
    override val version = 1L
    val sourceService: String = DELEGATION_SOURCE_SERVICE
}
