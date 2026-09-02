// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationResourceType
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class SpendReservationEventsTest {
    private val createdAt = OffsetDateTime.parse("2026-09-01T10:15:30.123456789+02:00")
    private val canonicalCreatedAt = OffsetDateTime.parse("2026-09-01T10:15:30.123456+02:00")
    private val reservationId = UUID.fromString("0199a111-0000-7000-8000-000000000001")
    private val grantId = UUID.fromString("0199a111-0000-7000-8000-000000000002")
    private val grant = DelegationGrant(
        id = grantId,
        grantorPartyId = UUID.fromString("0199a111-0000-7000-8000-000000000003"),
        granteePartyId = UUID.fromString("0199a111-0000-7000-8000-000000000004"),
        resourceType = DelegationResourceType.ACCOUNT,
        resourceId = UUID.fromString("0199a111-0000-7000-8000-000000000005"),
        capabilities = setOf(DelegationCapability.ACCOUNT_INITIATE_PAYMENT),
        dailyLimit = Money.of("5000.00", "CZK"),
        validFrom = createdAt.minusDays(1),
        validTo = createdAt.plusDays(30),
        status = DelegationStatus.ACTIVE,
        createdAt = createdAt.minusDays(2),
        updatedAt = createdAt.minusDays(1),
    )
    private val reserved = SpendReservation(
        id = reservationId,
        grantId = grantId,
        amount = Money.of("125.50", "CZK"),
        idempotencyKey = "payment-42",
        operationType = SpendReservationOperationType.DOMESTIC_PAYMENT,
        createdAt = createdAt,
    )

    @Test
    fun `snapshot contains complete tuple without the raw idempotency key`() {
        val event = DelegationSpendReservationStateChanged.from(reserved, grant)
        val wire = ObjectMapper().registerModule(JavaTimeModule())
            .valueToTree<com.fasterxml.jackson.databind.JsonNode>(event)

        assertThat(event.aggregateId).isEqualTo(reservationId)
        assertThat(event.delegationId).isEqualTo(grantId)
        assertThat(event.amount).isEqualByComparingTo("125.50")
        assertThat(event.idempotencyKeyHash)
            .isEqualTo("d5fcf99c283a194aff198754caa138862271e9f046af15e706ee317058ba9aad")
        assertThat(wire.has("idempotencyKey")).isFalse()
        assertThat(event.reservationVersion).isEqualTo(1L)
        assertThat(event.createdAt).isEqualTo(canonicalCreatedAt)
        assertThat(event.occurredAt).isEqualTo(canonicalCreatedAt.toInstant())
    }

    @Test
    fun `compaction key is bounded by the two valid revisions`() {
        assertThat(DelegationSpendReservationStateChanged.compactionKey(reservationId, 1L))
            .isEqualTo("$reservationId:v1")
        assertThat(DelegationSpendReservationStateChanged.compactionKey(reservationId, 2L))
            .isEqualTo("$reservationId:v2")
        assertThatThrownBy { DelegationSpendReservationStateChanged.compactionKey(reservationId, 3L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `terminal snapshots use revision two and canonical settlement time`() {
        val settledAt = createdAt.plusMinutes(2)
        val event = DelegationSpendReservationStateChanged.from(reserved.confirm(settledAt), grant)
        assertThat(event.state).isEqualTo(SpendReservationState.CONFIRMED)
        assertThat(event.reservationVersion).isEqualTo(2L)
        assertThat(event.settledAt).isEqualTo(settledAt.withNano(123456000))
    }

    @Test
    fun `non domestic and non account tuples fail closed`() {
        assertThatThrownBy {
            DelegationSpendReservationStateChanged.from(
                reserved.copy(operationType = SpendReservationOperationType.UNSPECIFIED),
                grant,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DelegationSpendReservationStateChanged.from(
                reserved,
                grant.copy(resourceType = DelegationResourceType.CARD),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
