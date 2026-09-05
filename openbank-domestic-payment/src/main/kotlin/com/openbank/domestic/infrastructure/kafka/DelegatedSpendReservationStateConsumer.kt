// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.domestic.application.port.`in`.ApplyDelegatedSpendReservationStateUseCase
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Consumes full snapshots from delegation-service's compacted reservation state topic. */
@ApplicationScoped
class DelegatedSpendReservationStateConsumer(
    private val objectMapper: ObjectMapper,
    private val useCase: ApplyDelegatedSpendReservationStateUseCase,
) {
    private val log = Logger.getLogger(DelegatedSpendReservationStateConsumer::class.java)

    @Incoming(CHANNEL)
    suspend fun consume(payload: String) {
        val node = runCatching { objectMapper.readTree(payload) }
            .getOrElse { malformed("invalid JSON", it) }
        if (node.requiredText("eventType") != DelegatedSpendReservationSnapshot.SOURCE_EVENT_TYPE) {
            malformed("unexpected eventType")
        }
        val reservationId = node.requiredUuid("reservationId")
        if (node.requiredUuid("aggregateId") != reservationId) {
            malformed("aggregateId must equal reservationId")
        }
        val snapshot = DelegatedSpendReservationSnapshot(
            eventId = node.requiredUuid("eventId"),
            reservationId = reservationId,
            delegationId = node.requiredUuid("delegationId"),
            grantorPartyId = node.requiredUuid("grantorPartyId"),
            granteePartyId = node.requiredUuid("granteePartyId"),
            resourceType = node.requiredText("resourceType"),
            resourceId = node.requiredUuid("resourceId"),
            amount = node.requiredDecimal("amount"),
            currency = node.requiredText("currency"),
            idempotencyKeyHash = node.requiredText("idempotencyKeyHash"),
            operationType = node.requiredText("operationType"),
            reservationState = node.requiredReservationState(),
            reservationVersion = node.requiredLong("reservationVersion"),
            schemaVersion = node.requiredLong("version"),
            aggregateType = node.requiredText("aggregateType"),
            sourceService = node.requiredText("sourceService"),
            createdAt = node.requiredInstant("createdAt"),
            settledAt = node.optionalInstant("settledAt"),
            occurredAt = node.requiredInstant("occurredAt"),
        ).canonicalizedSourceTimestamps()
        EventRetry.withRetry(
            log = log,
            what = "delegated spend reservation projection",
            key = reservationId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) {
            useCase.apply(snapshot)
        }
    }

    private fun JsonNode.requiredText(field: String): String =
        get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: malformed("$field is required text")

    private fun JsonNode.requiredUuid(field: String): UUID = runCatching {
        UUID.fromString(requiredText(field))
    }.getOrElse { malformed("$field is not a UUID", it) }

    private fun JsonNode.requiredLong(field: String): Long =
        get(field)?.takeIf { it.isIntegralNumber }?.longValue() ?: malformed("$field is required integer")

    private fun JsonNode.requiredDecimal(field: String): BigDecimal =
        get(field)?.takeIf { it.isNumber }?.decimalValue() ?: malformed("$field is required decimal")

    private fun JsonNode.requiredReservationState(): DelegatedSpendReservationState = runCatching {
        DelegatedSpendReservationState.valueOf(requiredText("state"))
    }.getOrElse { malformed("state has unknown value", it) }

    private fun JsonNode.requiredInstant(field: String): Instant =
        optionalInstant(field) ?: malformed("$field is required instant")

    private fun JsonNode.optionalInstant(field: String): Instant? {
        val value = get(field) ?: return null
        if (value.isNull) return null
        if (!value.isTextual || value.asText().isBlank()) malformed("$field is not an instant")
        return runCatching { OffsetDateTime.parse(value.asText()).toInstant() }
            .getOrElse { malformed("$field is not an instant", it) }
    }

    private fun malformed(reason: String, cause: Throwable? = null): Nothing =
        throw IllegalArgumentException("Malformed delegated spend reservation state: $reason", cause)

    companion object {
        const val CHANNEL = "delegated-spend-reservation-state-in"
    }
}
