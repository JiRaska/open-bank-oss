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
import com.openbank.libs.messaging.SyntheticTaintKafkaRail
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Consumes full snapshots from delegation-service's compacted reservation state topic.
 *
 * ## Why the signature is `Message<String>` and not `payload: String`
 *
 * ADR-0252 (#8630). The synthetic taint travels as the `x-openbank-synthetic` RECORD HEADER, which
 * `OutboxKafkaHeaders` stamps on the producing side from delegation-service's persisted outbox
 * column. A handler declared over the payload alone cannot see any header — 40 of the fleet's 48
 * `@Incoming` handlers are in that state, which is why no consumer anywhere turned a record's
 * taint into the row it wrote. Taking the `Message` is the whole difference.
 *
 * The cost is that SmallRye switches from auto-ack to MANUAL the moment the signature changes, so
 * every terminal path below must settle exactly once. The observable behaviour is deliberately
 * unchanged: a malformed record and a failed projection both nack, which hands the record to this
 * channel's configured `failure-strategy` — the same thing a thrown exception did before.
 */
@ApplicationScoped
class DelegatedSpendReservationStateConsumer(
    private val objectMapper: ObjectMapper,
    private val useCase: ApplyDelegatedSpendReservationStateUseCase,
) {
    private val log = Logger.getLogger(DelegatedSpendReservationStateConsumer::class.java)

    @Incoming(CHANNEL)
    @Suppress("TooGenericExceptionCaught") // both a poison pill and a dependency failure must nack
    suspend fun consume(message: Message<String>) {
        try {
            // The rail is established around the WHOLE projection, not just the parse: it is what
            // makes the taint readable at the persistence boundary that writes the row, and what
            // makes any outbound REST call issued while handling this record carry the header. On
            // a Kafka thread there is no inbound HTTP request, so before this existed both of
            // those were structurally impossible (SyntheticTaintClientFilter's own KDoc).
            SyntheticTaintKafkaRail.withTaintFrom(message.taintHeaders()) { project(message.payload) }
        } catch (e: Exception) {
            log.errorf(e, "Delegated spend reservation projection failed, nacking")
            settle(message, e)
            return
        }
        settle(message)
    }

    private suspend fun project(payload: String) {
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

/**
 * ACK when [cause] is null, NACK otherwise. Manual acknowledgement is the handler's job now.
 *
 * Written `settle(message, cause)` rather than as an extension on purpose: that is the spelling
 * `check-event-handler-swallows.py` recognises as a manual negative acknowledgement (it matches
 * analytics-sink's `AnalyticsConsumer`), and an extension receiver is invisible to it — the gate
 * would read this catch as a log-and-ack swallow, which is the opposite of what it does.
 */
private suspend fun settle(message: Message<String>, cause: Throwable? = null) {
    val stage = if (cause == null) message.ack() else message.nack(cause)
    Uni.createFrom().completionStage(stage).awaitSuspending()
}

/**
 * Lifts a consumed record's headers, verbatim and with their casing intact.
 *
 * All of them rather than a single lookup: Kafka's own `lastHeader` is byte-exact, while
 * `SyntheticTaint.isTainted` matches case-insensitively on purpose — casing survives no transport
 * reliably, and a case-sensitive read here would make the taint vanish at exactly one hop. Absent
 * Kafka metadata (the in-memory connector, a replay) is not an error: no headers means no claim,
 * which means REAL.
 *
 * These two are top level and placed AFTER the class deliberately: they keep the consumer under
 * detekt's `TooManyFunctions` threshold (which fires AT 11, not above it), and a top-level
 * declaration written ABOVE a class silently steals the annotation intended for it (the
 * `@Path`/`McpEndpoint` 404, #3371).
 */
private fun Message<String>.taintHeaders(): Map<String, String?> {
    val meta = getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null) ?: return emptyMap()

    @Suppress("UNCHECKED_CAST")
    val record = meta as IncomingKafkaRecordMetadata<Any?, String>
    return record.headers?.associate { it.key() to it.value()?.toString(Charsets.UTF_8) } ?: emptyMap()
}
