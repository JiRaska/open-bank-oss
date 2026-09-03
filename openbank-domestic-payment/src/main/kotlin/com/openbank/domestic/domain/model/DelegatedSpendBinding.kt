// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.model

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

enum class DelegatedSpendReservationState { RESERVED, CONFIRMED, RELEASED }

enum class DelegatedSpendBindingState { PENDING, BOUND, FINALIZED_ABSENT }

/**
 * Immutable reservation snapshot consumed from delegation-service's compacted state topic.
 *
 * The tuple is intentionally complete. A newer revision may change only [reservationState],
 * [reservationVersion], [settledAt], [occurredAt] and [eventId]; changing any money, party,
 * resource or idempotency field is producer corruption and must fail rather than silently rebind a
 * payment authorization.
 */
@Suppress("LongParameterList")
data class DelegatedSpendReservationSnapshot(
    val eventId: UUID,
    val reservationId: UUID,
    val delegationId: UUID,
    val grantorPartyId: UUID,
    val granteePartyId: UUID,
    val resourceType: String,
    val resourceId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKeyHash: String,
    val operationType: String,
    val reservationState: DelegatedSpendReservationState,
    val reservationVersion: Long,
    val schemaVersion: Long,
    val aggregateType: String,
    val sourceService: String,
    val createdAt: Instant,
    val settledAt: Instant?,
    val occurredAt: Instant,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported reservation schema version: $schemaVersion" }
        require(aggregateType == AGGREGATE_TYPE) { "Unexpected aggregateType: $aggregateType" }
        require(sourceService == SOURCE_SERVICE) { "Unexpected sourceService: $sourceService" }
        require(resourceType == ACCOUNT_RESOURCE_TYPE) { "Domestic payment reservation must target ACCOUNT" }
        require(operationType == OPERATION_TYPE) { "Unexpected reservation operationType: $operationType" }
        require(amount > BigDecimal.ZERO) { "Reservation amount must be positive" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "Reservation currency must be an uppercase ISO code" }
        require(idempotencyKeyHash.matches(Regex("[0-9a-f]{64}"))) {
            "Reservation idempotencyKeyHash must be 64 lowercase hexadecimal characters"
        }
        when (reservationState) {
            DelegatedSpendReservationState.RESERVED -> {
                require(reservationVersion == RESERVED_VERSION) { "RESERVED must have reservationVersion 1" }
                require(settledAt == null) { "RESERVED must not have settledAt" }
            }

            DelegatedSpendReservationState.CONFIRMED,
            DelegatedSpendReservationState.RELEASED,
            -> {
                require(reservationVersion == TERMINAL_VERSION) {
                    "Terminal reservation state must have reservationVersion 2"
                }
                requireNotNull(settledAt) { "Terminal reservation state requires settledAt" }
            }
        }
    }

    fun hasSameImmutableTuple(other: DelegatedSpendReservationSnapshot): Boolean =
        reservationId == other.reservationId &&
            delegationId == other.delegationId &&
            grantorPartyId == other.grantorPartyId &&
            granteePartyId == other.granteePartyId &&
            resourceType == other.resourceType &&
            resourceId == other.resourceId &&
            amount.compareTo(other.amount) == 0 &&
            currency == other.currency &&
            idempotencyKeyHash == other.idempotencyKeyHash &&
            operationType == other.operationType &&
            schemaVersion == other.schemaVersion &&
            aggregateType == other.aggregateType &&
            sourceService == other.sourceService &&
            createdAt.toPostgresPrecision() == other.createdAt.toPostgresPrecision()

    /** PostgreSQL `TIMESTAMPTZ` stores microseconds; canonicalize before persistence/comparison. */
    fun canonicalizedSourceTimestamps(): DelegatedSpendReservationSnapshot = copy(
        createdAt = createdAt.toPostgresPrecision(),
        settledAt = settledAt?.toPostgresPrecision(),
        occurredAt = occurredAt.toPostgresPrecision(),
    )

    fun hasSameRevisionEvidence(other: DelegatedSpendReservationSnapshot): Boolean =
        reservationState == other.reservationState &&
            settledAt?.toPostgresPrecision() == other.settledAt?.toPostgresPrecision() &&
            occurredAt.toPostgresPrecision() == other.occurredAt.toPostgresPrecision()

    companion object {
        /** Producer-owned discriminator accepted by the projection; this service does not emit it. */
        const val SOURCE_EVENT_TYPE = "DelegationSpendReservationStateChanged"
        const val AGGREGATE_TYPE = "DelegationSpendReservation"
        const val SOURCE_SERVICE = "delegation-service"
        const val ACCOUNT_RESOURCE_TYPE = "ACCOUNT"
        const val OPERATION_TYPE = "DOMESTIC_PAYMENT"
        const val SCHEMA_VERSION = 1L
        const val RESERVED_VERSION = 1L
        const val TERMINAL_VERSION = 2L

        /** Must remain byte-for-byte equal to delegation-service's producer helper. */
        const val IDEMPOTENCY_KEY_HASH_DOMAIN =
            "openbank.delegation.spend-reservation.idempotency-key.v1"

        fun hashIdempotencyKey(idempotencyKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(IDEMPOTENCY_KEY_HASH_DOMAIN.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            return HexFormat.of().formatHex(digest.digest(idempotencyKey.toByteArray(StandardCharsets.UTF_8)))
        }
    }
}

private fun Instant.toPostgresPrecision(): Instant = truncatedTo(ChronoUnit.MICROS)

@Suppress("LongParameterList")
data class DelegatedSpendBinding(
    val snapshot: DelegatedSpendReservationSnapshot,
    val bindingState: DelegatedSpendBindingState,
    val paymentId: UUID?,
    val observedAt: Instant,
    val boundAt: Instant?,
    val finalizedAt: Instant?,
    val updatedAt: Instant,
) {
    init {
        when (bindingState) {
            DelegatedSpendBindingState.PENDING -> {
                require(snapshot.reservationState == DelegatedSpendReservationState.RESERVED)
                require(snapshot.reservationVersion == DelegatedSpendReservationSnapshot.RESERVED_VERSION)
                require(paymentId == null && boundAt == null && finalizedAt == null)
            }

            DelegatedSpendBindingState.BOUND -> {
                requireNotNull(paymentId)
                requireNotNull(boundAt)
                require(finalizedAt == null)
            }

            DelegatedSpendBindingState.FINALIZED_ABSENT -> {
                require(paymentId == null && boundAt == null)
                requireNotNull(finalizedAt)
            }
        }
    }
}
