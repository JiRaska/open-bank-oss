// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.persistence.outbox.OutboxStatus
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.Instant
import java.util.UUID

/**
 * The wire shape sanctions-service puts on `openbank.sanctions.screening.event` for a monitoring
 * finding, for tests on the consuming side. Neither half is invented here:
 *
 *  - HEADERS come from [OutboxKafkaHeaders.headersFor], the function the producer's publisher
 *    calls, over an [OutboxEntry] whose `eventType` is the outbox row's type. That is where the
 *    event type travels (`ce-type`).
 *  - the BODY carries exactly the properties of `MonitoredSubjectMatchedPayload` in
 *    `openbank-contracts/openbank-sanctions-service/asyncapi.yaml` and therefore no `eventType`;
 *    `MonitoredSubjectMatchedConsumerTest` holds the key set to that schema.
 *
 * Public, not internal: the contract classes are compiled again in the provider-pact source set.
 */
object MonitoredSubjectMatchedWire {
    const val TOPIC = "openbank.sanctions.screening.event"
    const val EVENT_TYPE = "MONITORED_SUBJECT_MATCHED"
    const val OWNER = "openbank-kyb"

    private val mapper = jacksonObjectMapper()
    private val occurredAt: Instant = Instant.parse("2026-11-13T12:00:00Z")

    fun body(
        subjectRef: String,
        findingId: UUID = UUID.randomUUID(),
        ownerService: String = OWNER,
        matchKind: String = "SANCTIONS",
        matchStatus: String = "POTENTIAL_HIT",
    ): Map<String, Any> = mapOf(
        "schemaVersion" to 1,
        "aggregateId" to findingId.toString(),
        "aggregateType" to "MonitoringFinding",
        "findingId" to findingId.toString(),
        "ownerService" to ownerService,
        "subjectRef" to subjectRef,
        "entityType" to "INDIVIDUAL",
        "matchKind" to matchKind,
        "listType" to "EU_CONSOLIDATED",
        "listEntryRef" to "id:synthetic-entry-1",
        "listEntryVersion" to "0f".repeat(16),
        "matchScore" to 0.93,
        "matchStatus" to matchStatus,
        "detection" to "LIST_DELTA",
        "occurredAt" to occurredAt.toString(),
        "sourceService" to "sanctions-service",
    )

    fun json(body: Map<String, Any>): String = mapper.writeValueAsString(body)

    /** The outbox row the producer's dispatcher would hand to its publisher. */
    fun entry(eventType: String, body: String, aggregateId: UUID = UUID.randomUUID()) = OutboxEntry(
        eventId = UUID.randomUUID(),
        aggregateId = aggregateId,
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

    /** A record as the consumer receives it: the row payload verbatim, the outbox headers as sent. */
    fun record(eventType: String, body: String): ConsumerRecord<String, String> {
        val entry = entry(eventType, body)
        return record(body, headers(entry))
    }

    fun record(body: String, headers: Map<String, String>): ConsumerRecord<String, String> =
        ConsumerRecord(TOPIC, 0, 0L, "key", body).also { record ->
            headers.forEach { (name, value) -> record.headers().add(name, value.toByteArray()) }
        }
}
