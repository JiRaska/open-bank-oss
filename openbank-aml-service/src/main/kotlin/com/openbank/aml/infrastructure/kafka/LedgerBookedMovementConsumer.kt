// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.MovementIntakeRepository
import com.openbank.aml.domain.monitoring.BookedMovement
import com.openbank.libs.messaging.EventRetry
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Records every BOOKED movement of a customer account for transaction monitoring (ADR-0371 §1).
 *
 * The ledger publishes all of its events on one topic; only `AccountBookedChanged` is a booked
 * movement of a customer account, so everything else is ignored. That event is the proof of
 * booking: it is written by the ledger in the transaction that posts the journal. It carries the
 * account, currency and signed delta, but neither the owning party nor the rail — those are
 * attributed afterwards by the movement-attribution sweep, so this consumer touches nothing but its
 * own database and can never lose a movement because another service is down.
 *
 * Idempotent on the ledger's business key (journal entry, account, currency), not on the transport
 * event id: a ledger replay re-emits a historical booking under a FRESH event id.
 *
 * Failure handling: nothing is acked that was not recorded. An unreadable or incomplete payload
 * throws immediately — retrying it fails identically — and a database failure is retried and then
 * rethrown; in both cases the channel's dead-letter queue parks the record and the channel moves on.
 * A monitoring gap must be visible on the DLQ, never silent.
 */
@ApplicationScoped
class LedgerBookedMovementConsumer(
    private val repository: MovementIntakeRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = Logger.getLogger(LedgerBookedMovementConsumer::class.java)

    // The ledger writes `delta` as a JSON number. Jackson's default tree model reads that through a
    // double, which cannot hold every NUMERIC(19,4) value exactly.
    private val exactDecimals = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    @Incoming("ledger-events-in")
    suspend fun consume(payload: String) {
        val movement = try {
            parse(exactDecimals.readTree(payload))
        } catch (e: IllegalArgumentException) {
            meters.counter(METRIC, "outcome", "rejected").increment()
            throw e
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            meters.counter(METRIC, "outcome", "rejected").increment()
            throw IllegalArgumentException("ledger event is not JSON", e)
        }
        if (movement == null) {
            meters.counter(METRIC, "outcome", "ignored").increment()
            return
        }
        val recorded = EventRetry.withRetry(
            log,
            "booked movement intake",
            movement.journalEntryId,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) { repository.recordMovement(movement, clock.instant()) }
        meters.counter(METRIC, "outcome", if (recorded) "recorded" else "duplicate").increment()
    }

    companion object {
        const val EVENT_TYPE = "AccountBookedChanged"
        const val METRIC = "openbank.aml.monitoring.booked_movements"
        private const val MAX_SCALE = 4
        private const val MAX_INTEGER_DIGITS = 15

        /** `null` for any other event type on the shared ledger topic. */
        fun parse(node: JsonNode?): BookedMovement? {
            require(node != null && node.isObject) { "ledger event is not a JSON object" }
            if (node.path("eventType").asText() != EVENT_TYPE) return null
            return BookedMovement.fromSignedDelta(
                journalEntryId = uuid(node, "journalEntryId"),
                accountId = uuid(node, "aggregateId"),
                currency = text(node, "currency"),
                delta = decimal(node, "delta"),
                transactionId = uuid(node, "transactionId"),
                entryDate = runCatching { LocalDate.parse(text(node, "entryDate")) }
                    .getOrElse { throw IllegalArgumentException("entryDate is not a date", it) },
                bookedAt = runCatching { Instant.parse(text(node, "occurredAt")) }
                    .getOrElse { throw IllegalArgumentException("occurredAt is not an instant", it) },
                sourceEventId = node.path("eventId").asText("").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            )
        }

        private fun text(node: JsonNode, field: String): String {
            val value = node.path(field)
            require(value.isTextual && value.textValue().isNotBlank()) { "$field is missing" }
            return value.textValue()
        }

        private fun uuid(node: JsonNode, field: String): UUID = runCatching { UUID.fromString(text(node, field)) }
            .getOrElse { throw IllegalArgumentException("$field is not a UUID", it) }

        private fun decimal(node: JsonNode, field: String): BigDecimal {
            val value = node.path(field)
            val amount = when {
                value.isNumber -> value.decimalValue()
                value.isTextual -> runCatching { BigDecimal(value.textValue()) }
                    .getOrElse { throw IllegalArgumentException("$field is not a decimal", it) }
                else -> throw IllegalArgumentException("$field is missing")
            }
            // The column is NUMERIC(19,4); Postgres would round a fifth decimal without saying so.
            val exact = amount.stripTrailingZeros()
            require(exact.scale() <= MAX_SCALE && exact.precision() - exact.scale() <= MAX_INTEGER_DIGITS) {
                "$field does not fit NUMERIC(19,4)"
            }
            return amount
        }
    }
}
