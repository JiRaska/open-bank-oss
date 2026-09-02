// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.util.UUID

/**
 * ADR-0249 D4 — "the grantee's first use of a new authority notifies the grantor" (issue #5728).
 *
 * **What "first use" means here, and why the producer decides it.** It is the first spend
 * reservation ever taken against this grant: the moment the delegate converts an authority the
 * grantor gave into a claim on the grantor's money. Only delegation-service can answer that
 * question, because only it holds every reservation for the grant and can ask inside the same
 * transaction that takes the row-level write lock on the grant (see
 * `SpendReservationRepositoryImpl.reserve`). A consumer of the event stream cannot: it would have
 * to keep its own durable "have I seen this grant spend before" cursor, and two consumers would
 * disagree the first time either one lost its state.
 *
 * **Exactly once per grant, by construction rather than by convention.** The event is appended in
 * the same transaction as the reservation insert, and only when the grant had no prior reservation
 * row under the lock that already serialises every reserve on this grant. So a rollback takes the
 * event with it, a concurrent second reserve counts one prior row and emits nothing, and an
 * idempotent replay of the first reserve creates no row and therefore no second event. Nothing
 * downstream has to de-duplicate — which matters because notification-service deliberately has no
 * idempotency (its own KDoc), so a producer emitting this twice would push the grantor twice.
 *
 * **Not gated on `spend-reservation-state-events-enabled`, and not restricted to
 * DOMESTIC_PAYMENT.** That flag guards the compacted per-reservation state stream
 * ([DelegationSpendReservationStateChanged]) whose consumers must be deployed first. This is a
 * one-per-grant customer-facing signal on the long-lived lifecycle topic, and an authority whose
 * first use went unannounced because a *different* stream's rollout had not finished is exactly
 * the silent-disposition failure D4 exists to prevent.
 *
 * **Why `aggregateId` is the GRANT.** `OutboxKafkaHeaders.partitionKey` returns `aggregateId`, so
 * keying on the grant puts first-use and a later revoke of the same grant on one partition in
 * order. The reservation keeps its own id in [reservationId].
 *
 * **No `lifecycleRevision`, deliberately.** That field is the projections' monotonic
 * status-transition cursor, and first use changes no status — minting a revision for it would make
 * a non-transition compete with real ones in a comparison whose whole value is that it counts
 * transitions. Both in-tree consumers of this topic
 * (`CardDelegationEventConsumer`, account-service's `DelegationEventConsumer`) route unknown event
 * types to `else -> Unit` after parsing the three ids this event does carry, so they ack and
 * ignore it rather than poison-pilling on it.
 *
 * **No `sourceService` property.** `KafkaDelegationOutboxEventPublisher` stamps it onto every
 * outgoing payload — see its KDoc for why the stamp lives on the channel's single exit rather than
 * on each event class. The eight lifecycle events in this package declare none either.
 */
data class DelegationFirstUsed(
    override val aggregateId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    /** The reservation that constituted the first use — the unit of spend, not the authority. */
    val reservationId: UUID,
    val amount: EventMoney,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "DelegationGrant"
    override val eventType = EVENT_TYPE
    override val version = 1L

    init {
        require(grantorPartyId != granteePartyId) {
            "a grant's grantor and grantee are different parties"
        }
    }

    companion object {
        const val EVENT_TYPE = "DelegationFirstUsed"

        /**
         * [occurredAt] is the reservation's own `createdAt` — when the delegate actually reached
         * for the money — never `Instant.now()` at publish time and never a sentinel. audit-service
         * substitutes its own ingest time for a producer that sends no event time, and
         * `audit_entries` is append-only at the database, so a row that records the wrong instant
         * cannot be corrected afterwards.
         */
        fun from(reservation: SpendReservation, grant: DelegationGrant): DelegationFirstUsed {
            require(grant.id == reservation.grantId) {
                "reservation ${reservation.id} belongs to ${reservation.grantId}, not grant ${grant.id}"
            }
            return DelegationFirstUsed(
                aggregateId = grant.id,
                grantorPartyId = grant.grantorPartyId,
                granteePartyId = grant.granteePartyId,
                resourceType = grant.resourceType,
                resourceId = grant.resourceId,
                reservationId = reservation.id,
                amount = EventMoney(reservation.amount.amount, reservation.amount.currency.code),
                occurredAt = reservation.createdAt.toInstant(),
            )
        }
    }
}
