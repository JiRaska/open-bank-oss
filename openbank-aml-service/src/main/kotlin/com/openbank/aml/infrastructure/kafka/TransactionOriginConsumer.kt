// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.MovementIntakeRepository
import com.openbank.libs.messaging.EventRetry
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * Remembers the RAIL of each transaction, from the originating domain event (ADR-0371 §1).
 *
 * `TransactionInitiated` is not proof that money moved — that is the ledger's event — but it is the
 * only event that names the rail. It is kept keyed by transaction id so the attribution sweep can
 * join a booked movement to its rail. Nothing is counted here. The other event types on the topic
 * (completed, failed, reversed) carry no rail and are ignored.
 *
 * Same failure rule as the ledger consumer: an unreadable payload or a failed write is rethrown so
 * the dead-letter queue parks it.
 */
@ApplicationScoped
class TransactionOriginConsumer(
    private val repository: MovementIntakeRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = Logger.getLogger(TransactionOriginConsumer::class.java)

    @Incoming("transaction-events-in")
    suspend fun consume(payload: String) {
        val origin = try {
            parse(objectMapper.readTree(payload))
        } catch (e: IllegalArgumentException) {
            meters.counter(METRIC, "outcome", "rejected").increment()
            throw e
        } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
            meters.counter(METRIC, "outcome", "rejected").increment()
            throw IllegalArgumentException("transaction event is not JSON", e)
        }
        if (origin == null) {
            meters.counter(METRIC, "outcome", "ignored").increment()
            return
        }
        val recorded = EventRetry.withRetry(
            log,
            "transaction origin intake",
            origin.first,
            isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
        ) { repository.recordOrigin(origin.first, origin.second, clock.instant()) }
        meters.counter(METRIC, "outcome", if (recorded) "recorded" else "duplicate").increment()
    }

    companion object {
        const val EVENT_TYPE = "TransactionInitiated"
        const val METRIC = "openbank.aml.monitoring.transaction_origins"

        /** The producer's own value for a transaction that named no rail. */
        const val UNKNOWN_RAIL = "UNKNOWN"
        private val RAIL = Regex("[A-Z][A-Z0-9_]{0,31}")

        /** transaction id to rail, or `null` for any other event type on the topic. */
        fun parse(node: JsonNode?): Pair<UUID, String>? {
            require(node != null && node.isObject) { "transaction event is not a JSON object" }
            if (node.path("eventType").asText() != EVENT_TYPE) return null
            val id = node.path("aggregateId")
            require(id.isTextual) { "aggregateId is missing" }
            val transactionId = runCatching { UUID.fromString(id.textValue()) }
                .getOrElse { throw IllegalArgumentException("aggregateId is not a UUID", it) }
            val rail = node.path("rail").asText("").ifBlank { UNKNOWN_RAIL }
            require(rail.matches(RAIL)) { "rail is not a rail code" }
            return transactionId to rail
        }
    }
}
