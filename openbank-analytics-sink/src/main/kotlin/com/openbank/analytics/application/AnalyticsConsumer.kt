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
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
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

    @Inject lateinit var attribution: IngestAttributionMetrics

    private val log = Logger.getLogger(AnalyticsConsumer::class.java)

    /**
     * Consumes a `Message<String>`, not a bare `String`, on purpose (issue #2598).
     *
     * An outbox-relayed record's BODY is `OutboxEntry.payload` — the bare domain event. Its
     * addressing lives on the transport: the event type in the `ce-type` header, the aggregate id
     * as the record key, the producing domain as the topic. A `String` signature throws all three
     * away before the mapping runs, which is why plainly identifiable events (a passkey
     * enrolment, a generated document, a signing-ceremony step) landed in bronze as
     * UNKNOWN/UNKNOWN/unknown with nothing erroring.
     */
    @Incoming("analytics-events-in")
    suspend fun consume(message: Message<String>) {
        val payload = message.payload
        val address = addressOf(message)
        try {
            val node: JsonNode = objectMapper.readTree(payload)
            val envelope = toEnvelope(node, address)
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
            // #2598 ask 2: a row that lands without attribution must be counted and logged, or
            // the broken state stays indistinguishable from the healthy one.
            if (::attribution.isInitialized) attribution.record(envelope, address.topic)
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
        } finally {
            // Switching the signature from `String` to `Message<String>` also switches SmallRye
            // from auto-ack to manual, so the ack has to be explicit — and in a `finally`, or a
            // quarantined message would stall the partition forever.
            Uni.createFrom().completionStage(message.ack()).awaitSuspending()
        }
    }

    /** Lifts the broker metadata this consumer used to discard. Absent metadata is not an error. */
    private fun addressOf(message: Message<String>): EventAddress {
        val meta = message.getMetadata(IncomingKafkaRecordMetadata::class.java).orElse(null)
            ?: return EventAddress.NONE

        @Suppress("UNCHECKED_CAST")
        val record = meta as IncomingKafkaRecordMetadata<Any?, String>
        val ceType = record.headers
            ?.lastHeader(OutboxKafkaHeaders.HEADER_EVENT_TYPE)
            ?.value()
            ?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
        return EventAddress(
            topic = record.topic?.takeIf { it.isNotBlank() },
            key = record.key?.toString()?.takeIf { it.isNotBlank() },
            ceType = ceType,
        )
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Maps a raw domain event into the canonical, PII-masked [AnalyticsEnvelope]. Visible for tests.
     *
     * Kept as a single-argument overload for the backfill path, which replays stored bodies and has
     * no broker metadata to offer.
     */
    fun toEnvelope(node: JsonNode): AnalyticsEnvelope = toEnvelope(node, EventAddress.NONE)

    /**
     * As above, but with the broker addressing the record arrived with.
     *
     * Fallback ORDER matters and is deliberately body-first: the topic is consulted only where the
     * body already yielded the UNKNOWN sentinel. Putting the topic ahead of the body would rebucket
     * events that are attributed correctly today — `openbank.balance.events` carries an `accountId`
     * and is filed under ACCOUNT, and moving it to BALANCE would split an existing aggregate across
     * two buckets in the silver views. This change is additive by construction: it can only turn an
     * UNKNOWN into a value.
     */
    fun toEnvelope(node: JsonNode, address: EventAddress): AnalyticsEnvelope {
        val aggregateType = resolveAggregateType(node, address)
        return AnalyticsEnvelope(
            eventId = node["eventId"]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    // ADR-0106: a synthesised dedupe key is a durable, indexed identifier -> UUIDv7.
                ?: Ids.newId(),
            aggregateType = aggregateType,
            aggregateId = resolveAggregateId(node, aggregateType, address),
            aggregateVersion = resolveAggregateVersion(node),
            // `ce-type` is the outbox event type; a bare payload has no eventType field at all.
            eventType = node["eventType"]?.asText() ?: address.ceType ?: UNKNOWN,
            occurredAt = node["occurredAt"]?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.now(clock),
            sourceService = node["sourceService"]?.asText()
                ?: TopicAttribution.sourceService(address.topic)
                ?: UNKNOWN_SERVICE,
            schemaVersion = node["schemaVersion"]?.asInt() ?: 1,
            actorId = node["requestedBy"]?.asText() ?: node["actorId"]?.asText(),
            actorType = node["actorType"]?.asText(),
            traceId = node["traceId"]?.asText() ?: node["correlationId"]?.asText(),
            ingestedAt = Instant.now(clock),
            payload = PayloadMasker.maskToMap(node["payload"] ?: node),
        )
    }

    /**
     * The id MUST agree with aggregateType — bronze_events is keyed (aggregate_type,
     * aggregate_id) and silver_current_state reduces per that key. An independent precedence
     * chain here (it used to prefer accountId over transactionId) would pair
     * aggregate_type=TRANSACTION with an ACCOUNT id, collapsing every transaction on one
     * account into a single aggregate in silver — a corrupted read model that no test over
     * one event can see. Resolve the id FROM the resolved type instead.
     */
    private fun resolveAggregateId(node: JsonNode, aggregateType: String, address: EventAddress): String =
        node["aggregateId"]?.asText()
            ?: idForType(aggregateType, node)
            // The outbox partition key IS the aggregate id (OutboxKafkaHeaders.partitionKey).
            ?: address.key
            ?: UNKNOWN_SERVICE


    private fun resolveAggregateType(node: JsonNode, address: EventAddress): String = node["aggregateType"]?.asText()
        ?: inferAggregateType(node).takeIf { it != UNKNOWN }
        ?: TopicAttribution.aggregateType(address.topic)
        ?: UNKNOWN

    private fun resolveAggregateVersion(node: JsonNode): Long = node["aggregateVersion"]?.asLong()
        ?: node["version"]?.asLong()
        ?: node["sequenceNumber"]?.asLong()
        ?: 0L

    /**
     * The identifying field for a resolved aggregate type — the counterpart to [inferAggregateType],
     * kept adjacent so the two cannot drift. A type added to one without the other yields
     * `aggregate_id = "unknown"`, which is visible in bronze rather than silently wrong.
     */
    private fun idForType(type: String, node: JsonNode): String? = when (type) {
        "TRANSACTION" -> node["transactionId"]?.asText()
        "CONSENT" -> node["consentId"]?.asText()
        "KYC_CASE" -> node["kycCaseId"]?.asText()
        "DOCUMENT" -> node["documentId"]?.asText()
        "PASSKEY" -> node["credentialId"]?.asText()
        "ACCOUNT" -> node["accountId"]?.asText()
        "PARTY" -> node["partyId"]?.asText()
        else -> null
    } ?: node["accountId"]?.asText() ?: node["partyId"]?.asText()

    /**
     * Last-resort domain inference for an event whose envelope omits `aggregateType`.
     *
     * ORDER IS LOAD-BEARING, most specific first. A transaction event carries BOTH `transactionId`
     * and `accountId`; with `accountId` tested first — as it was — every transaction landed in bronze
     * as `ACCOUNT`. That went unnoticed only because the sink was subscribed to
     * `openbank.transaction.events`, a topic that has never existed, so no transaction event had ever
     * arrived to be misclassified. Correcting the topic name (same commit) is what would have made
     * the latent bug live, which is why both changes belong together: the fix and the thing the fix
     * would otherwise have broken.
     *
     * Same reason `documentId` precedes `accountId`/`partyId`: a signing-ceremony payload carries
     * `ceremonyId` + `documentId`, and a generated-document payload carries `documentId` +
     * `templateCode`. Both were landing as `UNKNOWN/UNKNOWN` in the sandbox — identifiable events
     * with three columns of attribution silently blank (#2598).
     *
     * This remains a HEURISTIC and is deliberately the BODY-first fallback: the broker address
     * ([EventAddress.topic]) is consulted only when the body yields UNKNOWN (see the [toEnvelope]
     * KDoc for why topic-first would rebucket correctly attributed events). The `@Incoming`
     * consumer now reads `Message<String>` and hands the addressing down here — but the body
     * still wins, so EVERY new event shape whose keys are not listed above AND whose topic is
     * not in [TopicAttribution] still silently becomes UNKNOWN, and nothing goes red.
     */
    private fun inferAggregateType(node: JsonNode): String = when {
        node.has("transactionId") -> "TRANSACTION"
        node.has("consentId") -> "CONSENT"
        node.has("kycCaseId") -> "KYC_CASE"
        node.has("documentId") -> "DOCUMENT"
        node.has("credentialId") -> "PASSKEY"
        node.has("accountId") -> "ACCOUNT"
        node.has("partyId") -> "PARTY"
        else -> UNKNOWN
    }

    companion object {
        private const val UNKNOWN = IngestAttributionMetrics.UNKNOWN
        private const val UNKNOWN_SERVICE = IngestAttributionMetrics.UNKNOWN_SERVICE
    }
}
