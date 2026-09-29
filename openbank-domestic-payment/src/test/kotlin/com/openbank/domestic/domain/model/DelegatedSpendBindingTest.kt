// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DelegatedSpendBindingTest {
    private val createdAt = Instant.parse("2026-05-01T10:00:00.123456789Z")
    private val reservation = DelegatedSpendReservationSnapshot(
        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        reservationId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        delegationId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        grantorPartyId = UUID.fromString("00000000-0000-0000-0000-000000000004"),
        granteePartyId = UUID.fromString("00000000-0000-0000-0000-000000000005"),
        resourceType = "ACCOUNT",
        resourceId = UUID.fromString("00000000-0000-0000-0000-000000000006"),
        amount = BigDecimal("125.00"),
        currency = "CZK",
        idempotencyKeyHash = "a".repeat(64),
        operationType = "DOMESTIC_PAYMENT",
        reservationState = DelegatedSpendReservationState.RESERVED,
        reservationVersion = 1,
        schemaVersion = 1,
        aggregateType = "DelegationSpendReservation",
        sourceService = "delegation-service",
        createdAt = createdAt,
        settledAt = null,
        occurredAt = createdAt,
    )

    @Test
    fun `immutable reservation tuple rejects every changed identity or money field`() {
        val differentId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        val changes = mapOf(
            "reservationId" to reservation.copy(reservationId = differentId),
            "delegationId" to reservation.copy(delegationId = differentId),
            "grantorPartyId" to reservation.copy(grantorPartyId = differentId),
            "granteePartyId" to reservation.copy(granteePartyId = differentId),
            "resourceId" to reservation.copy(resourceId = differentId),
            "amount" to reservation.copy(amount = BigDecimal("125.01")),
            "currency" to reservation.copy(currency = "EUR"),
            "idempotencyKeyHash" to reservation.copy(idempotencyKeyHash = "b".repeat(64)),
            "createdAt" to reservation.copy(createdAt = createdAt.plusSeconds(1)),
        )

        assertThat(reservation.hasSameImmutableTuple(reservation)).isTrue()
        changes.forEach { (field, changed) ->
            assertThat(reservation.hasSameImmutableTuple(changed)).describedAs(field).isFalse()
        }
        // Only PostgreSQL's sub-microsecond truncation may differ on persisted timestamps or
        // BigDecimal's scale; neither changes the authorized amount or creation instant.
        assertThat(reservation.hasSameImmutableTuple(reservation.copy(amount = BigDecimal("125.000")))).isTrue()
        assertThat(
            reservation.hasSameImmutableTuple(
                reservation.copy(createdAt = Instant.parse("2026-05-01T10:00:00.123456001Z")),
            ),
        ).isTrue()
    }

    @Test
    fun `revision evidence distinguishes state settlement and occurrence but ignores event id`() {
        val settled = createdAt.plusSeconds(1)
        val confirmed = reservation.copy(
            reservationState = DelegatedSpendReservationState.CONFIRMED,
            reservationVersion = 2,
            settledAt = settled,
            occurredAt = settled,
        )
        assertThat(confirmed.hasSameImmutableTuple(reservation)).isTrue()
        assertThat(confirmed.hasSameRevisionEvidence(confirmed.copy(eventId = UUID.randomUUID()))).isTrue()
        assertThat(confirmed.hasSameRevisionEvidence(reservation)).isFalse()
        val released = confirmed.copy(reservationState = DelegatedSpendReservationState.RELEASED)
        assertThat(confirmed.hasSameRevisionEvidence(released)).isFalse()
        assertThat(confirmed.hasSameRevisionEvidence(confirmed.copy(settledAt = settled.plusSeconds(1)))).isFalse()
        assertThat(confirmed.hasSameRevisionEvidence(confirmed.copy(occurredAt = settled.plusSeconds(1)))).isFalse()
        assertThat(
            confirmed.hasSameRevisionEvidence(confirmed.copy(settledAt = settled.plusNanos(1))),
        ).isTrue()
        assertThat(confirmed.canonicalizedSourceTimestamps().settledAt).isEqualTo(
            Instant.parse("2026-05-01T10:00:01.123456Z"),
        )
    }

    @Test
    fun `idempotency key hash is byte-identical to the producer test vector`() {
        assertThat(DelegatedSpendReservationSnapshot.hashIdempotencyKey("payment-42")).isEqualTo(
            "d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad",
        )
    }
}
