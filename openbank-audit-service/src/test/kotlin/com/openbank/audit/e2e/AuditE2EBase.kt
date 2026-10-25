// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.eclipse.microprofile.config.ConfigProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Base for the audit-service E2E suite: publish an audit event on the real Redpanda, let the
 * consumer append it to the tamper-evident chain in Postgres, and read the trail back over the
 * regulated HTTP surface. Concrete suites carry `@QuarkusTest` +
 * `@TestProfile(AuditE2EProfile::class)` — the annotations do not inherit, and Quarkus needs them
 * on the concrete class to register the test class as a CDI bean.
 */
abstract class AuditE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** The durable row as the assertions need it, or null when the consumer has not landed it. */
    data class EntryRow(val id: UUID, val eventType: String, val actorId: String?, val sourceService: String)

    /** Publishes [payload] on the ingestion topic. */
    protected fun publishAuditEvent(payload: String) {
        val bootstrap = ConfigProvider.getConfig()
            .getValue("kafka.bootstrap.servers", String::class.java)
        KafkaProducer<String, String>(
            mapOf(
                "bootstrap.servers" to bootstrap,
                "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
                "acks" to "all",
            ),
        ).use { producer ->
            producer.send(ProducerRecord(INGESTION_TOPIC, payload)).get(10, TimeUnit.SECONDS)
        }
    }

    /** A full-envelope audit event naming every attribution field. */
    protected fun auditEvent(
        eventId: UUID,
        aggregateId: UUID,
        actorId: String,
        eventType: String = "ACCOUNT_CREATED",
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "eventId" to eventId.toString(),
            "eventType" to eventType,
            "aggregateType" to "Account",
            "aggregateId" to aggregateId.toString(),
            "actorId" to actorId,
            "actorType" to "HUMAN",
            "sourceService" to "party-service",
            "occurredAt" to Instant.now().toString(),
            "correlationId" to UUID.randomUUID().toString(),
        ),
    )

    /** Polls until the consumer lands the row (the consumer projects asynchronously). */
    protected fun awaitEntry(eventId: UUID): EntryRow {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            entryRow(eventId)?.let { return it }
            Thread.sleep(150)
        }
        error("no audit_entries row appeared for event $eventId")
    }

    /** The durable row for the event, or null when not yet ingested. */
    protected fun entryRow(eventId: UUID): EntryRow? = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT entry_id, event_type, actor_id, source_service FROM audit_entries WHERE entry_id = ?",
        ).use { ps ->
            ps.setObject(1, eventId)
            val rs = ps.executeQuery()
            if (rs.next()) {
                EntryRow(
                    id = rs.getObject("entry_id", UUID::class.java),
                    eventType = rs.getString("event_type"),
                    actorId = rs.getString("actor_id"),
                    sourceService = rs.getString("source_service"),
                )
            } else {
                null
            }
        }
    }

    /** GET [path] expecting [expectedStatus], parsed as JSON. */
    protected fun getJson(path: String, expectedStatus: Int): JsonNode {
        val raw = Given { this } When {
            get(path)
        } Then {
            statusCode(expectedStatus)
        } Extract {
            asString()
        }
        return if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)
    }

    protected companion object {
        const val INGESTION_TOPIC = "openbank.audit.ingestion-test"
    }
}
