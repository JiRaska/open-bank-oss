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
import com.openbank.libs.messaging.EventRetry
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import com.openbank.libs.synthetic.SyntheticTaint
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
 * sink (and ClickHouse `ReplacingMergeTree`) collapse duplicates.
 *
 * **Two failures, two different answers (#5698/#5745).**
 *
 * A **malformed or un-projectable event** cannot be fixed by replaying it, so it is handed to the
 * [DeadLetterSink] and ACKED. Read [DeadLetterSink] for what that actually buys today: the only
 * binding is `LoggingDeadLetterSink`, a WARN line. The message is recoverable from the log pipeline
 * for as long as logs are retained (Loki, 1 week here) — that is a far weaker guarantee than the
 * "visible, counted and replayable" this KDoc used to claim. The ClickHouse `dead_letter_events`
 * table exists but nothing writes to it — see [DeadLetterSink].
 *
 * A **failed sink write** is the opposite: the event is fine, ClickHouse is not. This used to take
 * the same branch — the bronze row was dropped, a WARN was logged, and the message was acked. Bronze
 * is the ≥10-year log of record and the sole extraction path, so a dropped row is a permanent,
 * unreconstructable hole in it, and nothing downstream reports one: consumer lag is zero (the message
 * was acked) and the row simply is not there. Sink writes now go through [EventRetry.withRetry] and,
 * on persistent failure, the message is **NACKed** — see [consume] for why nack rather than a rethrow,
 * and for why neither KDoc names the channel's current `failure-strategy`.
 */
// TooManyFunctions: 11, i.e. exactly AT detekt's threshold, which it reports. The class was at 10
// and the #5745 fix adds exactly one — [settle] — because manual acknowledgement now has two
// possible outcomes instead of the single `finally { ack() }` it replaces. Splitting the envelope
// mapping out to buy headroom would move a dozen attribution fallbacks away from the KDocs that
// explain why their ORDER is load-bearing, which is a worse trade than one suppression.
@Suppress("TooManyFunctions")
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
    @Suppress("TooGenericExceptionCaught") // the two catches below mean opposite things; see the KDoc
    suspend fun consume(message: Message<String>) {
        val payload = message.payload
        val address = addressOf(message)

        // ---- Un-projectable payload: the poison pill. Quarantine and ACK; a replay fails the same.
        val envelope = try {
            toEnvelope(objectMapper.readTree(payload), address)
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
            settle(message)
            return
        }

        // F7: schema governance — an unknown/newer-than-known schema is quarantined (when strict),
        // never silently written into the 10-year log of record. Also a producer-side problem a
        // replay cannot fix, so it acks too.
        if (::schemaGovernance.isInitialized && !schemaGovernance.accept(envelope.eventType, envelope.schemaVersion)) {
            deadLetters.quarantine(
                DeadLetterRecord(
                    contentHash = sha256(payload),
                    rawPayload = payload,
                    error = "unknown schema ${envelope.eventType}:${envelope.schemaVersion}",
                    failedAt = Instant.now(clock),
                ),
            )
            settle(message)
            return
        }

        // ---- Sink write: a dependency failure, NOT a bad event. Retry, then nack.
        try {
            EventRetry.withRetry(log, "analytics bronze write", envelope.eventId) {
                sink.write(envelope)
            }
        } catch (e: Exception) {
            // NACK rather than rethrow, because this handler took manual ack the moment its
            // signature became `Message<String>`: acknowledgement is this method's job, and nack is
            // how it says "I did not do this work". A bare rethrow past a manual-ack handler leaves
            // the outcome to the framework rather than stating it here.
            //
            // What the nack buys is the connector's `failure-strategy` for `analytics-events-in`,
            // which is CONFIGURATION and not a property of this code: `dead-letter-queue` parks the
            // record in the configured topic, SmallRye's default `fail` stops the channel. This
            // comment deliberately does not state today's value — #5745 section B found the whole
            // #5698 family asserting a dead-letter that only four channels fleet-wide actually had,
            // and #5751 is wiring this one to `openbank.dlq.analytics-sink.analytics-events-in`.
            // Either outcome beats the old behaviour, which was to ack: a halted channel or a parked
            // record is recoverable, a silent hole in a ≥10-year log of record is not.
            log.errorf(e, "Bronze write failed after retries, nacking: %s", payload.take(200))
            settle(message, e)
            return
        }

        // #2598 ask 2: a row that lands without attribution must be counted and logged, or
        // the broken state stays indistinguishable from the healthy one.
        if (::attribution.isInitialized) attribution.record(envelope, address.topic)
        // F8: record ingest lag (now - occurredAt) so freshness/RPO can be alerted on.
        if (::freshness.isInitialized) freshness.recordIngest(envelope.occurredAt)
        settle(message)
    }

    /**
     * Settle the message: ACK when [cause] is null, NACK otherwise.
     *
     * Switching the signature from `String` to `Message<String>` switched SmallRye from auto-ack to
     * manual, so every terminal path in [consume] must state its own outcome — exactly once, and
     * never both. That is why this is no longer a `finally`: a `finally` acked the failed write too,
     * which is the whole defect. One function rather than an `ack`/`nack` pair so the class stays
     * under detekt's TooManyFunctions threshold without splitting the mapping apart.
     */
    private suspend fun settle(message: Message<String>, cause: Throwable? = null) {
        val stage = if (cause == null) message.ack() else message.nack(cause)
        Uni.createFrom().completionStage(stage).awaitSuspending()
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
        val synthetic = record.headers
            ?.lastHeader(SyntheticTaint.KAFKA_HEADER)
            ?.value()
            ?.toString(Charsets.UTF_8)
            ?.let(SyntheticTaint::isTainted)
            ?: false
        return EventAddress(
            topic = record.topic?.takeIf { it.isNotBlank() },
            key = record.key?.toString()?.takeIf { it.isNotBlank() },
            ceType = ceType,
            synthetic = synthetic,
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
            synthetic = address.synthetic,
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

    /**
     * The aggregate type, UPPERCASED (issue #4553).
     *
     * The producer's spelling used to survive verbatim while both fallbacks below emit uppercase, so
     * the value recorded WHICH PATH attributed the event rather than what the aggregate is. Bronze
     * ended up holding `ACCOUNT` (294 rows) and `Account` (17) for the same domain — five account ids
     * under both — and `silver_current_state`, which groups by `(aggregate_type, aggregate_id)`, then
     * emitted TWO current-state rows for one account. Last-writer-wins cannot fire across a group
     * boundary, so a reader filtering one spelling got a stale row presented as current.
     *
     * `Transaction` and `Consent` existed ONLY in mixed case, which is why every consumer comparing
     * against `'TRANSACTION'` matched nothing at all, and a Grafana tile asking for `'Party'` read 0
     * against a true 4 for its whole life (fixed in #4556).
     *
     * Uppercasing also repairs [idForType], whose `when (type)` matches uppercase literals only: a
     * mixed-case type fell through to the `accountId`/`partyId` fallback — the exact pairing of a
     * TRANSACTION type with an ACCOUNT id that [resolveAggregateId]'s KDoc exists to prevent.
     *
     * Rows already in bronze keep their original spelling; this stops the split growing, it does not
     * heal it. The backfill is tracked separately on #4553 — bronze is a `ReplacingMergeTree`, so
     * re-keying is an explicit CORRECTION ingest, not an in-place update.
     *
     * A downstream consumer of this invariant: `V5__party_accounts.sql`'s `silver_party_accounts`
     * view (ADR-0210 D2, #4520) still folds `upper(aggregate_type)` itself rather than assuming every
     * future row already arrives uppercase — the view has no way to tell a pre-fix bronze row from a
     * post-fix one, so it keeps the defence rather than deleting it now that the source produces one.
     */
    private fun resolveAggregateType(node: JsonNode, address: EventAddress): String = (
        node["aggregateType"]?.asText()
            ?: inferAggregateType(node).takeIf { it != UNKNOWN }
            ?: TopicAttribution.aggregateType(address.topic)
            ?: UNKNOWN
        ).uppercase()

    private fun resolveAggregateVersion(node: JsonNode): Long = node["aggregateVersion"]?.asLong()
        ?: node["version"]?.asLong()
        ?: node["sequenceNumber"]?.asLong()
        ?: 0L

    /**
     * The identifying field for a resolved aggregate type — the counterpart to [inferAggregateType],
     * kept adjacent so the two cannot drift. A type added to one without the other yields
     * `aggregate_id = "unknown"`, which is visible in bronze rather than silently wrong.
     */
    private fun idForType(type: String, node: JsonNode): String? = ID_FIELD_BY_TYPE[type]?.let { node[it]?.asText() }
        ?: node["accountId"]?.asText()
        ?: node["partyId"]?.asText()

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
    private fun inferAggregateType(node: JsonNode): String =
        ID_FIELD_BY_TYPE.entries.firstOrNull { (_, field) -> node.has(field) }?.key ?: UNKNOWN

    companion object {
        private const val UNKNOWN = IngestAttributionMetrics.UNKNOWN
        private const val UNKNOWN_SERVICE = IngestAttributionMetrics.UNKNOWN_SERVICE

        /**
         * Aggregate type -> the payload field that identifies it. ONE table, read by both
         * [inferAggregateType] (scanned in order, first match wins) and [idForType] (looked up).
         *
         * The two used to be parallel `when` chains, and [idForType]'s KDoc asked for them to be
         * "kept adjacent so the two cannot drift" — adjacency is a weaker promise than a shared
         * table, and it was already being tested only by the fact that nobody had broken it. A type
         * present in one and missing from the other yields `aggregate_id = "unknown"`; here that is
         * not expressible.
         *
         * ORDER IS LOAD-BEARING, most specific first, and a LinkedHashMap is what preserves it.
         * A transaction event carries BOTH `transactionId` and `accountId`; with `accountId` tested
         * first — as it once was — every transaction landed in bronze as ACCOUNT. `documentId`
         * precedes `accountId`/`partyId` for the same reason: signing-ceremony and generated-document
         * payloads carry it alongside others and were landing as UNKNOWN/UNKNOWN (#2598).
         *
         * The four #8792 domains sit ABOVE `accountId`/`partyId` because their payloads carry those
         * too: a card issuance carries cardId, partyId AND accountId, so placing it below would file
         * it as ACCOUNT while its sibling CardStatusChanged — carrying only cardId — fell through to
         * the topic and became CARD. One domain, two aggregate types, split by which fields an event
         * happens to have. Reordering is safe for these four and only these four, measured rather
         * than argued: of the 1712 rows in bronze, ZERO carry cardId, loanId, orderId or
         * conversionId, so no existing event can be rebucketed. That property does not hold for the
         * entries above them, which is why those stay put.
         */
        private val ID_FIELD_BY_TYPE: Map<String, String> = linkedMapOf(
            "TRANSACTION" to "transactionId",
            "CONSENT" to "consentId",
            "KYC_CASE" to "kycCaseId",
            "DOCUMENT" to "documentId",
            "PASSKEY" to "credentialId",
            "CARD" to "cardId",
            "LENDING" to "loanId",
            "STANDING_ORDER" to "orderId",
            "FX" to "conversionId",
            "ACCOUNT" to "accountId",
            "PARTY" to "partyId",
        )
    }
}
