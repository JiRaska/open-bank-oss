// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.domestic.domain.event.toFinalizedAbsentEvent
import com.openbank.domestic.domain.model.DelegatedSpendBinding
import com.openbank.domestic.domain.model.DelegatedSpendBindingState
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

internal object DelegatedSpendFinalizedAbsentPactFixture {
    const val PROVIDER_STATE = "a delegated spend reservation is pending without a domestic payment"
    const val INTERACTION = "a delegated spend reservation finalized absent event"

    private val finalizedAt = Instant.parse("2026-09-01T12:10:00Z")
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    fun payload(): String = mapper.writeValueAsString(binding().toFinalizedAbsentEvent())

    fun binding() = DelegatedSpendBinding(
        snapshot = DelegatedSpendReservationSnapshot(
            eventId = UUID.fromString("20000000-0000-4000-8000-000000000001"),
            reservationId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
            delegationId = UUID.fromString("20000000-0000-4000-8000-000000000003"),
            grantorPartyId = UUID.fromString("20000000-0000-4000-8000-000000000004"),
            granteePartyId = UUID.fromString("20000000-0000-4000-8000-000000000005"),
            resourceType = DelegatedSpendReservationSnapshot.ACCOUNT_RESOURCE_TYPE,
            resourceId = UUID.fromString("20000000-0000-4000-8000-000000000006"),
            amount = BigDecimal("1500.00"),
            currency = "CZK",
            idempotencyKeyHash = DelegatedSpendReservationSnapshot.hashIdempotencyKey("payment-42"),
            operationType = DelegatedSpendReservationSnapshot.OPERATION_TYPE,
            reservationState = DelegatedSpendReservationState.RESERVED,
            reservationVersion = DelegatedSpendReservationSnapshot.RESERVED_VERSION,
            schemaVersion = DelegatedSpendReservationSnapshot.SCHEMA_VERSION,
            aggregateType = DelegatedSpendReservationSnapshot.AGGREGATE_TYPE,
            sourceService = DelegatedSpendReservationSnapshot.SOURCE_SERVICE,
            createdAt = Instant.parse("2026-09-01T12:00:00Z"),
            settledAt = null,
            occurredAt = Instant.parse("2026-09-01T12:00:00Z"),
        ),
        bindingState = DelegatedSpendBindingState.FINALIZED_ABSENT,
        paymentId = null,
        observedAt = Instant.parse("2026-09-01T12:00:05Z"),
        boundAt = null,
        finalizedAt = finalizedAt,
        updatedAt = finalizedAt,
    )
}
