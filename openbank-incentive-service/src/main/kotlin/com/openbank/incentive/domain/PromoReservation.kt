// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.domain

import java.time.Instant
import java.util.UUID

@JvmInline
value class CodeDigest(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "code digest must be a SHA-256 hex value" }
    }
}

enum class ReservationStatus { RESERVED, COMMITTED, RELEASED, EXPIRED }

data class PromoReservation(
    val id: UUID,
    val offerRef: OfferRef,
    val codeDigest: CodeDigest,
    val partyRef: String,
    val productRef: String,
    val idempotencyKey: String,
    val reservedAt: Instant,
    val expiresAt: Instant,
    val attributionRef: UUID? = null,
    val status: ReservationStatus = ReservationStatus.RESERVED,
) {
    init {
        require(partyRef.isNotBlank()) { "party reference is required" }
        require(productRef.isNotBlank()) { "product reference is required" }
        require(idempotencyKey.isNotBlank()) { "idempotency key is required" }
        require(reservedAt < expiresAt) { "reservation expiry must follow reservation time" }
    }

    fun commit(at: Instant): PromoReservation {
        if (status == ReservationStatus.COMMITTED) return this
        if (status != ReservationStatus.RESERVED) throw IncentiveConflict("only a reservation can be committed")
        if (!at.isBefore(expiresAt)) throw IncentiveConflict("an expired reservation cannot be committed")
        return copy(status = ReservationStatus.COMMITTED)
    }

    fun release(): PromoReservation {
        if (status == ReservationStatus.RELEASED) return this
        if (status != ReservationStatus.RESERVED) throw IncentiveConflict("only a reservation can be released")
        return copy(status = ReservationStatus.RELEASED)
    }

    fun expire(at: Instant): PromoReservation {
        if (status == ReservationStatus.EXPIRED) return this
        if (status != ReservationStatus.RESERVED) throw IncentiveConflict("only a reservation can expire")
        if (at.isBefore(expiresAt)) throw IncentiveConflict("reservation is not expired")
        return copy(status = ReservationStatus.EXPIRED)
    }
}
