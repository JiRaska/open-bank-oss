// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.libs.domain.event.DomainEvent
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/**
 * Full current state of one delegated-spend reservation.
 *
 * This is deliberately a snapshot rather than a transition delta. Its Kafka topic is compacted by
 * the bounded `(reservationId, reservationVersion)` key returned by [compactionKey], retaining at
 * most RESERVED and terminal for one reservation. A bootstrap groups by [reservationId] and
 * applies only the greatest [reservationVersion], never the record that happened to arrive last.
 * Terminal records are ordinary values, never Kafka tombstones; a delayed ambiguous retry of
 * RESERVED therefore cannot compact away the evidence that headroom was consumed or released.
 */
@Suppress("LongParameterList")
data class DelegationSpendReservationStateChanged(
    override val aggregateId: UUID,
    val reservationId: UUID,
    val delegationId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: DelegationResourceType,
    val resourceId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKeyHash: String,
    val operationType: SpendReservationOperationType,
    val state: SpendReservationState,
    val reservationVersion: Long,
    val createdAt: OffsetDateTime,
    val settledAt: OffsetDateTime?,
    override val occurredAt: Instant,
    val sourceService: String = SOURCE_SERVICE,
) : DomainEvent(occurredAt) {
    override val aggregateType = AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = SCHEMA_VERSION

    init {
        require(aggregateId == reservationId) { "aggregateId must equal reservationId" }
        require(operationType == SpendReservationOperationType.DOMESTIC_PAYMENT) {
            "only DOMESTIC_PAYMENT reservations enter the domestic state stream"
        }
        require(resourceType == DelegationResourceType.ACCOUNT) {
            "a domestic-payment reservation must target an account"
        }
        require(reservationVersion == versionFor(state)) {
            "reservationVersion $reservationVersion does not match state $state"
        }
    }

    companion object {
        const val EVENT_TYPE = "DelegationSpendReservationStateChanged"
        const val AGGREGATE_TYPE = "DelegationSpendReservation"
        const val SCHEMA_VERSION = 1L
        const val RESERVED_VERSION = 1L
        const val TERMINAL_VERSION = 2L
        const val SOURCE_SERVICE = "delegation-service"
        const val IDEMPOTENCY_KEY_HASH_DOMAIN =
            "openbank.delegation.spend-reservation.idempotency-key.v1"

        fun hashIdempotencyKey(idempotencyKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(IDEMPOTENCY_KEY_HASH_DOMAIN.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            return HexFormat.of().formatHex(digest.digest(idempotencyKey.toByteArray(StandardCharsets.UTF_8)))
        }

        fun compactionKey(reservationId: UUID, reservationVersion: Long): String {
            require(reservationVersion == RESERVED_VERSION || reservationVersion == TERMINAL_VERSION) {
                "reservationVersion must be $RESERVED_VERSION or $TERMINAL_VERSION"
            }
            return "$reservationId:v$reservationVersion"
        }

        fun from(reservation: SpendReservation, grant: DelegationGrant): DelegationSpendReservationStateChanged {
            require(grant.id == reservation.grantId) {
                "reservation ${reservation.id} belongs to ${reservation.grantId}, not grant ${grant.id}"
            }
            val canonicalCreatedAt = reservation.createdAt.truncatedTo(ChronoUnit.MICROS)
            val canonicalSettledAt = reservation.settledAt?.truncatedTo(ChronoUnit.MICROS)
            return DelegationSpendReservationStateChanged(
                aggregateId = reservation.id,
                reservationId = reservation.id,
                delegationId = reservation.grantId,
                grantorPartyId = grant.grantorPartyId,
                granteePartyId = grant.granteePartyId,
                resourceType = grant.resourceType,
                resourceId = grant.resourceId,
                amount = reservation.amount.amount,
                currency = reservation.amount.currency.code,
                idempotencyKeyHash = hashIdempotencyKey(reservation.idempotencyKey),
                operationType = reservation.operationType,
                state = reservation.state,
                reservationVersion = versionFor(reservation.state),
                createdAt = canonicalCreatedAt,
                settledAt = canonicalSettledAt,
                occurredAt = (canonicalSettledAt ?: canonicalCreatedAt).toInstant(),
            )
        }

        private fun versionFor(state: SpendReservationState): Long = when (state) {
            SpendReservationState.RESERVED -> RESERVED_VERSION
            SpendReservationState.CONFIRMED,
            SpendReservationState.RELEASED,
            -> TERMINAL_VERSION
        }
    }
}
