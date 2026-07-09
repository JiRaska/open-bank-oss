// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.libs.analytics.AnalyticsEnvelope
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Default
import org.jboss.logging.Logger

/**
 * Default [AnalyticsSink] that emits each envelope as a single structured log line.
 *
 * It needs no external system, so the service boots, is unit-testable, and is offline-buildable
 * with zero infrastructure. The line is shaped to be ingestable by a log pipeline
 * (Fluent Bit / Vector) into ClickHouse if the native client isn't wired up yet.
 *
 * The durable path is [ClickHouseAnalyticsSink], a ClickHouse-backed `@Alternative @Priority(100)`
 * adapter (activated at build time via `openbank.analytics.sink.type=clickhouse`) — mirrors the
 * [com.openbank.libs.audit.LoggingAuditEventPublisher] fallback pattern. This logging binding
 * exists so a missing warehouse connection never *silently drops* analytics records; the bronze
 * layer is the log of record (ADR-0022) and losing it forfeits the ability to recompute / reconcile.
 *
 * SAFETY: this only logs already-masked, structural fields (ids, type, version, timestamps,
 * provenance). It deliberately does NOT log [AnalyticsEnvelope.payload] — even though the payload
 * is PII-masked at the consumer, replaying a full body into logs is needless exposure (GDPR Art. 25).
 */
@ApplicationScoped
@Default
class LoggingAnalyticsSink : AnalyticsSink {
    private val log = Logger.getLogger("openbank.analytics")

    override suspend fun write(envelope: AnalyticsEnvelope) {
        log.infof(
            "analytics row eventId=%s type=%s aggregate=%s/%s version=%d eventType=%s source=%s schemaV=%d occurredAt=%s traceId=%s",
            envelope.eventId,
            envelope.aggregateType,
            envelope.aggregateType,
            envelope.aggregateId,
            envelope.aggregateVersion,
            envelope.eventType,
            envelope.sourceService,
            envelope.schemaVersion,
            envelope.occurredAt,
            envelope.traceId ?: "-",
        )
    }
}
