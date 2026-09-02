// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.sink

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.analytics.AnalyticsEnvelope
import com.openbank.libs.analytics.AnalyticsIntegrity
import com.openbank.libs.analytics.IngestSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Plain-JUnit tests for the ClickHouse bronze adapter's pure row-mapping and the dedupe/insert path.
 * No ClickHouse server: [ClickHouseAnalyticsSink.send] is overridden to capture the JSONEachRow body,
 * so the test verifies exactly what would be POSTed. Keeps the module offline-runnable.
 */
class ClickHouseAnalyticsSinkTest {

    private val mapper = ObjectMapper()

    /** Captures the body that would be sent to ClickHouse instead of opening an HTTP connection. */
    private class CapturingSink(mapper: ObjectMapper) : ClickHouseAnalyticsSink() {
        var lastBody: String? = null
        var sendCount = 0

        init {
            this.mapper = mapper
            url = "http://clickhouse:8123"
            database = "openbank_analytics"
            username = "analytics"
            password = Optional.of("secret")
        }

        override suspend fun send(body: String) {
            lastBody = body
            sendCount++
        }
    }

    private fun envelope(
        eventId: UUID = UUID.randomUUID(),
        version: Long = 1,
        payload: Map<String, Any?> = emptyMap(),
    ) = AnalyticsEnvelope(
        eventId = eventId,
        aggregateType = "ACCOUNT",
        aggregateId = "acc-42",
        aggregateVersion = version,
        eventType = "account.changed",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        sourceService = "openbank-account-service",
        schemaVersion = 3,
        actorId = "user-1",
        actorType = "ROLE_OPERATOR",
        traceId = "trace-9",
        ingestSource = IngestSource.STREAM,
        ingestedAt = Instant.parse("2026-01-02T03:04:05.678Z"),
        payload = payload,
    )

    @Test
    fun `bronze row carries every column name and the F1 record hash`() {
        val env = envelope(payload = mapOf("balance" to 1234, "active" to true))
        val sink = CapturingSink(mapper)

        val node = mapper.readTree(sink.bronzeRowJson(env))

        assertThat(node.get("event_id").asText()).isEqualTo(env.eventId.toString())
        assertThat(node.get("aggregate_type").asText()).isEqualTo("ACCOUNT")
        assertThat(node.get("aggregate_id").asText()).isEqualTo("acc-42")
        assertThat(node.get("aggregate_version").asLong()).isEqualTo(1)
        assertThat(node.get("event_type").asText()).isEqualTo("account.changed")
        assertThat(node.get("source_service").asText()).isEqualTo("openbank-account-service")
        assertThat(node.get("schema_version").asInt()).isEqualTo(3)
        assertThat(node.get("ingest_source").asText()).isEqualTo("STREAM")
        assertThat(node.get("synthetic").asBoolean()).isFalse()
        // ClickHouse DateTime64(3,'UTC') literal format, in UTC.
        assertThat(node.get("occurred_at").asText()).isEqualTo("2026-01-01 00:00:00.000")
        assertThat(node.get("ingested_at").asText()).isEqualTo("2026-01-02 03:04:05.678")
        // F1: hash matches the canonical primitive, so tamper-evidence is identical to the libs view.
        assertThat(node.get("record_hash").asText()).isEqualTo(AnalyticsIntegrity.recordHash(env))
    }

    @Test
    fun `bronze row persists synthetic provenance`() {
        val node = mapper.readTree(sinkRow(envelope().copy(synthetic = true)))

        assertThat(node.get("synthetic").asBoolean()).isTrue()
    }

    private fun sinkRow(env: AnalyticsEnvelope): String = ClickHouseAnalyticsSink().apply {
        mapper = this@ClickHouseAnalyticsSinkTest.mapper
    }.bronzeRowJson(env)

    @Test
    fun `payload column is an embedded JSON string, not a nested object`() {
        val env = envelope(payload = mapOf("balance" to 1234))
        val sink = CapturingSink(mapper)

        val node = mapper.readTree(sink.bronzeRowJson(env))

        // payload is a String column → the value must be textual JSON, re-parseable to the map.
        assertThat(node.get("payload").isTextual).isTrue()
        val payload = mapper.readTree(node.get("payload").asText())
        assertThat(payload.get("balance").asInt()).isEqualTo(1234)
    }

    @Test
    fun `writeBatch dedupes on eventId before inserting`() = runBlocking<Unit> {
        val dup = UUID.randomUUID()
        val sink = CapturingSink(mapper)

        sink.writeBatch(listOf(envelope(eventId = dup, version = 1), envelope(eventId = dup, version = 2)))

        assertThat(sink.sendCount).isEqualTo(1)
        // Two envelopes, same eventId → one JSONEachRow line.
        assertThat(sink.lastBody!!.lines()).hasSize(1)
    }

    @Test
    fun `writeBatch emits one JSONEachRow line per distinct event`(): Unit = runBlocking {
        val sink = CapturingSink(mapper)

        sink.writeBatch(listOf(envelope(version = 1), envelope(version = 2), envelope(version = 3)))

        assertThat(sink.lastBody!!.lines()).hasSize(3)
        sink.lastBody!!.lines().forEach { line -> assertThat(mapper.readTree(line).isObject).isTrue() }
    }

    @Test
    fun `empty batch never opens a connection`() = runBlocking<Unit> {
        val sink = CapturingSink(mapper)

        sink.writeBatch(emptyList())

        assertThat(sink.sendCount).isEqualTo(0)
    }
}
