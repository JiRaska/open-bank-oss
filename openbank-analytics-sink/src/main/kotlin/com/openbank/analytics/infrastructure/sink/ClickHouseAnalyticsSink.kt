// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.libs.analytics.AnalyticsEnvelope
import com.openbank.libs.analytics.AnalyticsIntegrity
import com.openbank.libs.analytics.AnalyticsProjections
import io.quarkus.arc.properties.IfBuildProperty
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Optional

/**
 * Durable [AnalyticsSink] that writes the **bronze** layer into ClickHouse over its HTTP interface
 * (ADR-0022, and the F1 record-hash control of ADR-0023).
 *
 * This is the real adapter behind the `@Default` [LoggingAnalyticsSink] — same idiom as
 * [com.openbank.libs.audit.AuditEventPublisher]'s Kafka adapter. It is gated by the **build-time**
 * property `openbank.analytics.sink.type=clickhouse`; when absent (the default), this bean is removed
 * from the CDI container and the logging fallback stays active, so the service still boots and is
 * testable with zero infrastructure.
 *
 * Why the raw HTTP interface and not a JDBC driver: it adds no extra Maven dependency (the JDK
 * [HttpClient] is built in), so the module stays offline-buildable, and ClickHouse's
 * `INSERT ... FORMAT JSONEachRow` endpoint is the simplest idempotent bulk-insert path. Idempotency
 * is delegated to the table engine: `ReplacingMergeTree(aggregate_version)` keyed by
 * (aggregate_type, aggregate_id, event_id) collapses at-least-once duplicates at merge / `FINAL`,
 * exactly mirroring [AnalyticsProjections.latestPerAggregate]. The batch is also pre-deduped on
 * [AnalyticsEnvelope.eventId] before the insert so a single delivery never inserts the same row twice.
 *
 * Each row carries the [AnalyticsIntegrity.recordHash] tamper-evidence digest (F1); the Merkle root
 * over a batch's hashes is sealed separately by the WORM archive (F2). PII in [AnalyticsEnvelope.payload]
 * MUST already be masked by the consumer — this adapter never inspects or unmasks it.
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProperty(name = "openbank.analytics.sink.type", stringValue = "clickhouse")
open class ClickHouseAnalyticsSink : AnalyticsSink {

    @ConfigProperty(name = "openbank.analytics.clickhouse.url", defaultValue = "http://localhost:8123")
    lateinit var url: String

    @ConfigProperty(name = "openbank.analytics.clickhouse.database", defaultValue = "openbank_analytics")
    lateinit var database: String

    @ConfigProperty(name = "openbank.analytics.clickhouse.username", defaultValue = "analytics")
    lateinit var username: String

    // Optional<String>, not a plain String (CLAUDE.md pitfall): SmallRye's built-in String converter
    // treats an empty-string-resolved value as "no value" and throws SRCFG00040 at boot.
    @ConfigProperty(name = "openbank.analytics.clickhouse.password")
    lateinit var password: Optional<String>

    @Inject
    lateinit var mapper: ObjectMapper

    private val log = Logger.getLogger("openbank.analytics")
    private val http: HttpClient by lazy { HttpClient.newHttpClient() }

    override suspend fun write(envelope: AnalyticsEnvelope) = writeBatch(listOf(envelope))

    override suspend fun writeBatch(envelopes: List<AnalyticsEnvelope>) {
        if (envelopes.isEmpty()) return
        val deduped = AnalyticsProjections.dedupeByEventId(envelopes)
        val body = insertBody(deduped)
        send(body)
        log.debugf("clickhouse bronze insert: %d rows (deduped from %d)", deduped.size, envelopes.size)
    }

    /**
     * Builds the newline-delimited `JSONEachRow` payload for a batch — one JSON object per row whose
     * keys are the `bronze_events` column names. Pure and deterministic, so it is unit-testable
     * without a ClickHouse server.
     */
    internal fun insertBody(envelopes: List<AnalyticsEnvelope>): String =
        envelopes.joinToString("\n") { bronzeRowJson(it) }

    /** Serialises a single envelope into a `bronze_events` JSONEachRow object. */
    internal fun bronzeRowJson(env: AnalyticsEnvelope): String {
        val row = linkedMapOf<String, Any?>(
            "event_id" to env.eventId.toString(),
            "aggregate_type" to env.aggregateType,
            "aggregate_id" to env.aggregateId,
            "aggregate_version" to env.aggregateVersion,
            "event_type" to env.eventType,
            "occurred_at" to CLICKHOUSE_DT.format(env.occurredAt),
            "source_service" to env.sourceService,
            "schema_version" to env.schemaVersion,
            "actor_id" to env.actorId,
            "actor_type" to env.actorType,
            "trace_id" to env.traceId,
            "ingest_source" to env.ingestSource.name,
            "batch_id" to env.batchId,
            "ingested_at" to CLICKHOUSE_DT.format(env.ingestedAt),
            // F1 tamper-evidence: deterministic over identity + business content (excludes lineage/time).
            "record_hash" to AnalyticsIntegrity.recordHash(env),
            "synthetic" to env.synthetic,
            // payload is a String column holding the (already PII-masked) body as embedded JSON.
            "payload" to mapper.writeValueAsString(env.payload),
        )
        return mapper.writeValueAsString(row)
    }

    /**
     * POSTs a JSONEachRow body to the ClickHouse HTTP insert endpoint. `open` so tests can capture the
     * request without a running server. Throws on a non-2xx response so the consumer's error path
     * (DLQ / freshness dead-letter) sees the failure instead of silently losing the row.
     */
    protected open suspend fun send(body: String) = withContext(Dispatchers.IO) {
        val query = "INSERT INTO $database.bronze_events FORMAT JSONEachRow"
        val uri = URI.create("${url.trimEnd('/')}/?query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}")
        val request = HttpRequest.newBuilder(uri)
            .header("X-ClickHouse-User", username)
            .header("X-ClickHouse-Key", password.orElse(""))
            .header("Content-Type", "text/plain; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "ClickHouse insert failed: HTTP ${response.statusCode()} ${response.body().take(500)}",
            )
        }
    }

    private companion object {
        // ClickHouse DateTime64(3,'UTC') literal format: 'yyyy-MM-dd HH:mm:ss.SSS'.
        val CLICKHOUSE_DT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}
