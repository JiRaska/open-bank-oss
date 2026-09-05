// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.entity

import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "domestic_delegated_spend_bindings")
class DelegatedSpendBindingEntity {
    @Id
    @Column(name = "reservation_id", nullable = false)
    lateinit var reservationId: UUID

    @Column(name = "delegation_id", nullable = false)
    lateinit var delegationId: UUID

    @Column(name = "grantor_party_id", nullable = false)
    lateinit var grantorPartyId: UUID

    @Column(name = "grantee_party_id", nullable = false)
    lateinit var granteePartyId: UUID

    @Column(name = "resource_type", nullable = false, length = 32)
    lateinit var resourceType: String

    @Column(name = "resource_id", nullable = false)
    lateinit var resourceId: UUID

    @Column(name = "operation_type", nullable = false, length = 32)
    lateinit var operationType: String

    @Column(name = "amount", nullable = false, precision = 20, scale = 6)
    lateinit var amount: BigDecimal

    @Column(name = "currency", nullable = false, length = 3)
    lateinit var currency: String

    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    lateinit var idempotencyKeyHash: String

    @Column(name = "reservation_state", nullable = false, length = 16)
    lateinit var reservationState: String

    @Column(name = "reservation_version", nullable = false)
    var reservationVersion: Long = 0

    @Column(name = "schema_version", nullable = false)
    var schemaVersion: Long = 0

    @Column(name = "aggregate_type", nullable = false, length = 64)
    lateinit var aggregateType: String

    @Column(name = "source_service", nullable = false, length = 64)
    lateinit var sourceService: String

    @Column(name = "source_created_at", nullable = false)
    lateinit var sourceCreatedAt: Instant

    @Column(name = "source_settled_at")
    var sourceSettledAt: Instant? = null

    @Column(name = "source_occurred_at", nullable = false)
    lateinit var sourceOccurredAt: Instant

    @Column(name = "last_event_id", nullable = false)
    lateinit var lastEventId: UUID

    @Column(name = "binding_state", nullable = false, length = 32)
    lateinit var bindingState: String

    @Column(name = "payment_id")
    var paymentId: UUID? = null

    @Column(name = "observed_at", nullable = false)
    lateinit var observedAt: Instant

    @Column(name = "bound_at")
    var boundAt: Instant? = null

    @Column(name = "finalized_at")
    var finalizedAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    /**
     * ADR-0252: the record that created this projection was a synthetic customer's (#8630).
     *
     * Persisted rather than inferred: the finalizer that turns this row into an outbox event runs
     * on a scheduler with no record and no request context, so ambient MDC/baggage is not
     * available to it — the same argument `OutboxMessage.synthetic` makes for the outbox row.
     * Monotonic: a later revision may raise it, never clear it.
     */
    @Column(name = "synthetic", nullable = false)
    var synthetic: Boolean = false

    fun toDomain(): DelegatedSpendBinding = DelegatedSpendBinding(
        snapshot = DelegatedSpendReservationSnapshot(
            eventId = lastEventId,
            reservationId = reservationId,
            delegationId = delegationId,
            grantorPartyId = grantorPartyId,
            granteePartyId = granteePartyId,
            resourceType = resourceType,
            resourceId = resourceId,
            amount = amount,
            currency = currency,
            idempotencyKeyHash = idempotencyKeyHash,
            operationType = operationType,
            reservationState = DelegatedSpendReservationState.valueOf(reservationState),
            reservationVersion = reservationVersion,
            schemaVersion = schemaVersion,
            aggregateType = aggregateType,
            sourceService = sourceService,
            createdAt = sourceCreatedAt,
            settledAt = sourceSettledAt,
            occurredAt = sourceOccurredAt,
        ),
        bindingState = DelegatedSpendBindingState.valueOf(bindingState),
        paymentId = paymentId,
        observedAt = observedAt,
        boundAt = boundAt,
        finalizedAt = finalizedAt,
        updatedAt = updatedAt,
    )
}
