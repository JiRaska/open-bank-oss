// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.application.port.out

import com.openbank.libs.analytics.AnalyticsEnvelope

/**
 * Outbound port: writes normalised [AnalyticsEnvelope] records into the analytics **bronze**
 * layer (ADR-0022).
 *
 * Implementations must be **idempotent on [AnalyticsEnvelope.eventId]** — Kafka is at-least-once,
 * so the same envelope can arrive more than once. The ClickHouse target encodes this with
 * `ReplacingMergeTree(aggregateVersion)` keyed by (aggregateType, aggregateId); the sink itself
 * pre-dedupes a batch via [com.openbank.libs.analytics.AnalyticsProjections.dedupeByEventId].
 *
 * The default binding is [com.openbank.analytics.infrastructure.sink.LoggingAnalyticsSink], which
 * needs no external infrastructure so the service boots and is testable with zero infra. The durable
 * binding is [com.openbank.analytics.infrastructure.sink.ClickHouseAnalyticsSink] — an
 * `@Alternative @Priority(100)` implementation activated at build time via
 * `openbank.analytics.sink.type=clickhouse`, exactly like [com.openbank.libs.audit.AuditEventPublisher].
 */
interface AnalyticsSink {

    /** Persists a single envelope. PII in [AnalyticsEnvelope.payload] MUST already be masked. */
    suspend fun write(envelope: AnalyticsEnvelope)

    /**
     * Persists a batch. Default folds to [write]; ClickHouse adapters should override with a
     * single bulk insert. The batch is de-duplicated on [AnalyticsEnvelope.eventId] first.
     */
    suspend fun writeBatch(envelopes: List<AnalyticsEnvelope>) {
        envelopes.forEach { write(it) }
    }
}
