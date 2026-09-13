// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.synthetic.SyntheticTaint

/**
 * Canonical broker addressing for a relayed outbox row (ADR-0003 N3 / ADR-0050 N2-N3).
 *
 * Every compliant publisher must:
 *  - use [partitionKey] (= the aggregate id) as the record key, so all events for one
 *    aggregate land on the same partition and keep their order (N2). A random key would
 *    scatter events and break downstream ordering/dedup.
 *  - carry the event id as the [HEADER_EVENT_ID] / [HEADER_IDEMPOTENCY_KEY] headers and the
 *    event type as [HEADER_EVENT_TYPE], so an at-least-once consumer can deduplicate (N3).
 *
 * Several services historically shipped a bare `Emitter<String>` with none of this, so their
 * events could not be ordered or deduplicated. Centralising the names + builder here makes the
 * compliant shape the path of least resistance.
 *
 * Returns a plain `Map` rather than Kafka's `RecordHeaders` on purpose: `openbank-libs` stays
 * free of a Kafka dependency, and the (thin) service-side publisher converts the map to whatever
 * the transport needs. Pure function — unit-tested.
 */
object OutboxKafkaHeaders {
    const val HEADER_EVENT_ID: String = "ce-id"
    const val HEADER_IDEMPOTENCY_KEY: String = "idempotency-key"
    const val HEADER_EVENT_TYPE: String = "ce-type"

    /** ADR-0252: emitted only for tainted records; absence means real activity. */
    const val HEADER_SYNTHETIC: String = SyntheticTaint.KAFKA_HEADER

    /** N2: partition key = aggregate id, so one aggregate's events keep their order. */
    fun partitionKey(entry: OutboxEntry): String = entry.aggregateId.toString()

    /** N3: event id as the consumer-visible idempotency key, plus the event type. */
    fun headersFor(entry: OutboxEntry): Map<String, String> {
        val eventId = entry.eventId.toString()
        return linkedMapOf(
            HEADER_EVENT_ID to eventId,
            HEADER_IDEMPOTENCY_KEY to eventId,
            HEADER_EVENT_TYPE to entry.eventType,
        ).also { headers ->
            if (entry.synthetic) headers[HEADER_SYNTHETIC] = SyntheticTaint.headerValue()
        }
    }
}
