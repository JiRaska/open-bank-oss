// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.analytics.application.port.out.AnalyticsSink
import com.openbank.analytics.application.port.out.DeadLetterRecord
import com.openbank.analytics.application.port.out.DeadLetterSink
import com.openbank.libs.analytics.AnalyticsEnvelope
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Consumes the same domain-event stream the audit service ingests (ADR-0003 outbox topics) and
 * normalises each event into an [AnalyticsEnvelope] for the bronze layer (ADR-0022).
 *
 * This is the *only* extraction path into analytics: there is no Debezium/WAL CDC reading the
 * operational databases, so reporting adds zero load on the OLTP side. PII is masked at this
 * boundary via [PayloadMasker] before anything is handed to the [AnalyticsSink], because the
 * bronze layer is retained ≥10 years and must never hold raw identifiers.
 *
 * Delivery is at-least-once; the envelope's [AnalyticsEnvelope.eventId] is the dedupe key and the
 * sink (and ClickHouse `ReplacingMergeTree`) collapse duplicates. A malformed event is **quarantined**
 * to the [DeadLetterSink] (not silently swallowed) so it is visible, counted and replayable once the
 * producer is fixed — a dropped event would be an invisible gap in the log of record.
 */
@ApplicationScoped
class AnalyticsConsumer {

    @Inject lateinit var sink: AnalyticsSink

    @Inject lateinit var clock: Clock

    @Inject lateinit var deadLetters: DeadLetterSink

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var schemaGovernance: SchemaGovernance

    @Inject lateinit var freshness: IngestFreshness

    private val log = Logger.getLogger(AnalyticsConsumer::class.java)

    @Incoming("analytics-events-in")
    suspend fun consume(payload: String) {
        try {
            val node: JsonNode = objectMapper.readTree(payload)
            val envelope = toEnvelope(node)
            // F7: schema governance — an unknown/newer-than-known schema is quarantined (when strict),
            // never silently written into the 10-year log of record.
            if (::schemaGovernance.isInitialized &&
                !schemaGovernance.accept(
                    envelope.eventType,
                    envelope.schemaVersion,
                )
            ) {
                deadLetters.quarantine(
                    DeadLetterRecord(
                        contentHash = sha256(payload),
                        rawPayload = payload,
                        error = "unknown schema ${envelope.eventType}:${envelope.schemaVersion}",
                        failedAt = Instant.now(clock),
                    ),
                )
                return
            }
            sink.write(envelope)
            // F8: record ingest lag (now - occurredAt) so freshness/RPO can be alerted on.
            if (::freshness.isInitialized) freshness.recordIngest(envelope.occurredAt)
        } catch (e: Exception) {
            log.errorf(e, "Quarantining un-projectable analytics message: %s", payload.take(200))
            deadLetters.quarantine(
                DeadLetterRecord(
                    contentHash = sha256(payload),
                    rawPayload = payload,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                    failedAt = Instant.now(clock),
                ),
            )
            if (::freshness.isInitialized) freshness.recordDeadLetter()
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Maps a raw domain event into the canonical, PII-masked [AnalyticsEnvelope]. Visible for tests. */
    fun toEnvelope(node: JsonNode): AnalyticsEnvelope {
        val aggregateType = node["aggregateType"]?.asText() ?: inferAggregateType(node)
        return AnalyticsEnvelope(
            eventId = node["eventId"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.randomUUID(),
            aggregateType = aggregateType,
            aggregateId = node["aggregateId"]?.asText()
                ?: node["accountId"]?.asText()
                ?: node["partyId"]?.asText()
                ?: node["transactionId"]?.asText()
                ?: node["consentId"]?.asText()
                ?: node["kycCaseId"]?.asText()
                ?: "unknown",
            aggregateVersion = node["aggregateVersion"]?.asLong()
                ?: node["version"]?.asLong()
                ?: node["sequenceNumber"]?.asLong()
                ?: 0L,
            eventType = node["eventType"]?.asText() ?: "UNKNOWN",
            occurredAt = node["occurredAt"]?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.now(clock),
            sourceService = node["sourceService"]?.asText() ?: "unknown",
            schemaVersion = node["schemaVersion"]?.asInt() ?: 1,
            actorId = node["requestedBy"]?.asText() ?: node["actorId"]?.asText(),
            actorType = node["actorType"]?.asText(),
            traceId = node["traceId"]?.asText() ?: node["correlationId"]?.asText(),
            ingestedAt = Instant.now(clock),
            payload = PayloadMasker.maskToMap(node["payload"] ?: node),
        )
    }

    private fun inferAggregateType(node: JsonNode): String = when {
        node.has("accountId") -> "ACCOUNT"
        node.has("partyId") -> "PARTY"
        node.has("transactionId") -> "TRANSACTION"
        node.has("consentId") -> "CONSENT"
        node.has("kycCaseId") -> "KYC_CASE"
        else -> "UNKNOWN"
    }
}
