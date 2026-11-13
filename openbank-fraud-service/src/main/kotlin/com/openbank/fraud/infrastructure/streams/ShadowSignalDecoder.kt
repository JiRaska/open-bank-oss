// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.streams

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.feature.TRANSACTION_INITIATED
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import org.apache.kafka.common.header.Headers
import org.apache.kafka.streams.processor.api.ContextualProcessor
import org.apache.kafka.streams.processor.api.Record
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** Validate before assigning event time: malformed/future input cannot advance stream time. */
internal class ShadowSignalDecoder(
    private val mapper: ObjectMapper,
    clock: Clock,
    private val outcome: (String) -> Unit,
) : ContextualProcessor<String, String, String, String>() {
    private val parser = ShadowSignalParser(mapper, clock)

    override fun process(record: Record<String, String>) {
        val signal = parser.decode(record.headers(), record.value(), outcome) ?: return
        val metadata = context().recordMetadata().orElse(null) ?: return outcome("missing_metadata")
        val envelope = mapper.createObjectNode()
            .put("eventId", signal.eventId)
            .put("accountId", signal.accountId)
            .put("occurredAt", signal.occurredAt.toString())
            .put("sourceTopic", metadata.topic())
            .put("sourcePartition", metadata.partition())
            .put("sourceOffset", metadata.offset())
        context().forward(
            record.withKey(
                signal.accountId,
            ).withValue(envelope.toString()).withTimestamp(signal.occurredAt.toEpochMilli()),
        )
        outcome("accepted")
    }
}

/** Used at the source boundary too, before Kafka Streams advances any task watermark. */
internal class ShadowSignalParser(private val mapper: ObjectMapper, private val clock: Clock) {
    fun decode(headers: Headers, payload: String?, outcome: (String) -> Unit): Signal? {
        val kind = headers.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)?.value()?.toString(Charsets.UTF_8)
        if (kind != ShadowVelocityTopology.INPUT_TOPIC) {
            outcome("ignored_type")
            return null
        }
        if (headers.lastHeader(OutboxKafkaHeaders.HEADER_SYNTHETIC) != null) {
            outcome("synthetic")
            return null
        }
        return parsePayload(headers, payload, outcome)
    }

    private fun parsePayload(headers: Headers, payload: String?, outcome: (String) -> Unit): Signal? = try {
        val body = mapper.readTree(payload)
        val headerId = headers.lastHeader(
            OutboxKafkaHeaders.HEADER_EVENT_ID,
        )?.value()?.toString(Charsets.UTF_8)
        require(body != null && body.isObject)
        require(body.path("eventType").asText() == TRANSACTION_INITIATED)
        val eventId = UUID.fromString(requireNotNull(headerId)).toString()
        val accountId = UUID.fromString(body.path("sourceAccountId").asText()).toString()
        val occurredAt = Instant.parse(body.path("occurredAt").asText())
        require(!occurredAt.isBefore(Instant.EPOCH))
        require(!occurredAt.isAfter(clock.instant().plus(FUTURE_TOLERANCE)))
        Signal(eventId, accountId, occurredAt)
    } catch (_: JacksonException) {
        outcome("invalid_payload")
        null
    } catch (_: IllegalArgumentException) {
        outcome("invalid_identity")
        null
    } catch (_: DateTimeParseException) {
        outcome("invalid_event_time")
        null
    }

    data class Signal(val eventId: String, val accountId: String, val occurredAt: Instant)

    private companion object {
        val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}
