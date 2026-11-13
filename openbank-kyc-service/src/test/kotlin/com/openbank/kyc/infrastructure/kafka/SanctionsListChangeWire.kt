// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.Instant
import java.util.UUID

/**
 * The wire shape sanctions-service actually puts on `openbank.sanctions.screening.event`, for
 * tests on the consuming side.
 *
 * Two halves, and neither is hand-invented:
 *  - HEADERS come from [OutboxKafkaHeaders.headersFor] — the same function the producer's
 *    publisher calls — over an [OutboxEntry] whose `eventType` is the outbox row's type. That is
 *    where the event type travels (`ce-type`).
 *  - BODIES carry exactly the producer contract's properties and therefore NO `eventType`;
 *    `SanctionsListChangedConsumerTest` holds the key sets to
 *    `openbank-contracts/openbank-sanctions-service/asyncapi.yaml`, which the
 *    event-contract-code-agreement gate in turn holds to the producing code.
 *
 * A body written with an `eventType` key is how the original consumer test passed against a
 * consumer that discarded every real record.
 */
object SanctionsListChangeWire {
    const val TOPIC = "openbank.sanctions.screening.event"
    const val EVENT_CHANGED = "SANCTIONS_LIST_CHANGED"
    const val EVENT_STORM = "SANCTIONS_LIST_CHANGE_STORM"

    private val mapper = jacksonObjectMapper()
    private val occurredAt: Instant = Instant.parse("2026-02-10T12:00:00Z")

    fun changedBody(publicationId: UUID, listId: UUID = UUID.randomUUID()): Map<String, Any> = mapOf(
        "listType" to "EU_CONSOLIDATED",
        "changedExternalIds" to listOf("ext-1", "ext-2"),
        "deactivatedExternalIds" to listOf("ext-9"),
        "changeCount" to 3,
        "publicationId" to publicationId.toString(),
        "chunkIndex" to 0,
        "chunkCount" to 1,
    ) + envelope(listId)

    fun stormBody(listId: UUID = UUID.randomUUID()): Map<String, Any> = mapOf(
        "listType" to "EU_CONSOLIDATED",
        "changeCount" to 800,
        "baselineEntryCount" to 1000,
        "reason" to "CHANGE_SHARE",
        "stormThresholdShare" to 0.5,
        "evidenceFingerprint" to "0".repeat(64),
    ) + envelope(listId)

    private fun envelope(listId: UUID): Map<String, Any> = mapOf(
        "aggregateId" to listId.toString(),
        "aggregateType" to "SanctionsList",
        "occurredAt" to occurredAt.toString(),
    )

    /** The outbox row the producer's dispatcher would hand to its publisher. */
    fun entry(eventType: String, body: String, listId: UUID = UUID.randomUUID()) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = listId,
        eventType = eventType,
        payload = body,
        status = OutboxStatus.DISPATCHING,
        attemptCount = 0,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        sentAt = null,
        lastError = null,
    )

    fun headers(entry: OutboxEntry): Map<String, String> = OutboxKafkaHeaders.headersFor(entry)

    fun json(body: Map<String, Any>): String = mapper.writeValueAsString(body)

    /** A record as the consumer receives it: key = aggregate id, value = the row payload verbatim. */
    fun record(eventType: String, body: String): ConsumerRecord<String, String> {
        val entry = entry(eventType, body)
        return record(body, headers(entry), OutboxKafkaHeaders.partitionKey(entry))
    }

    fun record(body: String, headers: Map<String, String>, key: String = "k"): ConsumerRecord<String, String> =
        ConsumerRecord(TOPIC, 0, 0L, key, body).also { record ->
            headers.forEach { (name, value) -> record.headers().add(name, value.toByteArray()) }
        }
}
