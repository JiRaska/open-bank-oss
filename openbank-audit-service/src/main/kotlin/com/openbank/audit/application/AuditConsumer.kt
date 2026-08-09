// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.libs.persistence.outbox.OutboxKafkaHeaders
import io.micrometer.core.instrument.MeterRegistry
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.Message
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class AuditConsumer {

    @Inject lateinit var repo: AuditRepository

    @Inject lateinit var objectMapper: ObjectMapper

    @Inject lateinit var clock: Clock

    @Inject lateinit var meterRegistry: MeterRegistry

    private val log = Logger.getLogger(AuditConsumer::class.java)

    /**
     * Records one audit event.
     *
     * **Why `Message<String>` and not `String` (#3994).** The old signature saw the message body
     * and nothing else, so two facts the transport was already carrying were discarded at ingest:
     * the outbox `ce-type` header (the event type — the body of an outbox-relayed event is the bare
     * domain event and usually has no `eventType` key at all) and the topic (which names the
     * producing service). Both fell through to the `"UNKNOWN"`/`"unknown"` sentinels, and 76% of
     * the live trail is that `"unknown"`. Same signature, same two defaults and the same fix as the
     * analytics sink's #2598.
     *
     * Body-first ordering is deliberate: broker metadata is consulted ONLY where the body yielded
     * nothing. A producer that populates the field keeps its own value, so this can only turn a
     * sentinel into a value — it can never re-attribute a row that is already attributed.
     *
     * **Nothing is rejected.** Every message that was stored before is still stored, with the same
     * or better attribution; a message with no metadata and no body fields still lands on the
     * sentinels rather than being dropped. An audit path that drops events is worse than one that
     * under-attributes them, so the fallbacks stay and only become visible ([AttributionSource],
     * `openbank.audit.attribution.missing`) instead of silent.
     */
    @Incoming("audit-events-in")
    suspend fun consume(message: Message<String>) {
        val payload = message.payload
        try {
            consume(payload, addressOf(message))
        } finally {
            // Switching the signature from `String` to `Message<String>` also switches SmallRye
            // from auto-ack to MANUAL ack, so the ack must be explicit — and in a `finally`, or an
            // un-storable message would stall the partition forever and the audit trail would stop
            // dead. (`consume` already swallows its own exceptions, so this is belt-and-braces.)
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
            ceType = ceType,
        )
    }

    /**
     * As above, for a record with no broker addressing to offer. Visible for tests and for any
     * replay path that has only the stored body.
     */
    suspend fun consume(payload: String): Unit = consume(payload, EventAddress.NONE)

    suspend fun consume(payload: String, address: EventAddress) {
        try {
            val node: JsonNode = objectMapper.readTree(payload)
            val eventTime = eventTime(node)
            val resolvedSource = resolveSourceService(node, address)
            val entry = AuditEntry(
                id = UUID.randomUUID(),
                // sepa.instant.events (KafkaSctInstEventPublisher) names its discriminator "type",
                // not "eventType" — the only #996-consumed producer that does so.
                // `ce-type` is the outbox event type, and it is the LAST resort before the
                // sentinel: it is the producer's own value, carried by the transport rather than
                // the body, so it is a recovery of a fact and not an inference (#3994).
                eventType = node.textOrNull("eventType")
                    ?: node.textOrNull("type")
                    ?: address.ceType
                    ?: "UNKNOWN",
                aggregateType = node.textOrNull("aggregateType") ?: inferAggregateType(node),
                aggregateId = inferAggregateId(node),
                actorId = node.textOrNull("requestedBy")
                    ?: node.textOrNull("actorId")
                    // transaction.initiated events carry the customer identity here (ADR-0021).
                    ?: node.textOrNull("initiatedByPartyId"),
                actorType = node.textOrNull("actorType"),
                payload = payload,
                sourceService = resolvedSource.first,
                sourceServiceSource = resolvedSource.second,
                correlationId = node.textOrNull("correlationId"),
                occurredAt = eventTime ?: Instant.now(clock),
                recordedAt = Instant.now(clock),
                occurredAtSource = if (eventTime != null) OccurredAtSource.EVENT else OccurredAtSource.INGEST,
                // ADR-0226: cross-channel dimensions, additive — producers adopt them channel by
                // channel, so absence stays null (unknown), never a guessed default.
                channel = node.textOrNull("channel"),
                actChain = node["actChain"]?.takeIf { it.isArray }?.map { it.asText() } ?: emptyList(),
                sessionId = node.textOrNull("sessionId"),
                // ADR-0232 D5: a delegated action names the grantor it was taken on behalf of and
                // the grant that permitted it. customer-edge flattens its audit details into the
                // event JSON, so both arrive as top-level fields. Absent = a direct action.
                onBehalfOf = node.textOrNull("onBehalfOf"),
                delegationId = node.textOrNull("delegationId"),
            )
            if (eventTime == null) countMissingEventTime(entry.sourceService)
            if (entry.sourceServiceSource != AttributionSource.EVENT) {
                countMissingAttribution(entry.sourceService, entry.sourceServiceSource)
            }
            repo.save(entry)
        } catch (e: Exception) {
            log.errorf(e, "Failed to record audit entry: %s", payload.take(200))
        }
    }

    /**
     * `openbank.audit.event.time.missing{source_service}` — events stored with ingest time because
     * their producer sent no usable `occurredAt` (#3883).
     *
     * The per-row [OccurredAtSource] flag makes the gap answerable in SQL after the fact; this
     * makes it answerable as a series, which is what turns "a producer regressed" into something
     * that can degrade a dashboard instead of waiting for an auditor. Tagged by producing service
     * only — the tag set is the fixed topic list, so cardinality is bounded.
     *
     * Guarded by `isInitialized` because unit tests construct this bean by hand; a missing
     * registry must never cost an audit row.
     */
    private fun countMissingEventTime(sourceService: String) {
        if (!::meterRegistry.isInitialized) return
        meterRegistry.counter("openbank.audit.event.time.missing", "source_service", sourceService)
            .increment()
    }

    /**
     * The producing service, and who said so (#3994).
     *
     * Ordered strongest claim first:
     *  1. the producer's own `sourceService` field — [AttributionSource.EVENT];
     *  2. the Kafka topic, via the verified [TopicAttribution] table — [AttributionSource.TOPIC].
     *     Sound because the topic is transport addressing rather than anything the producer chose
     *     to omit, but it identifies the service and is not the service's own assertion;
     *  3. neither — the `"unknown"` sentinel, [AttributionSource.ABSENT], exactly as before.
     *
     * The pair is returned together on purpose: a caller cannot take the value without also taking
     * the provenance, so a derived attribution cannot be stored as if it were declared. That is
     * the whole defect — `?: "unknown"` was a *successful parse* with no exception, no metric and
     * no log line, and 76% of the trail went that way unnoticed.
     */
    private fun resolveSourceService(node: JsonNode, address: EventAddress): Pair<String, AttributionSource> {
        node.textOrNull("sourceService")?.let {
            return it to AttributionSource.EVENT
        }
        TopicAttribution.sourceService(address.topic)?.let { return it to AttributionSource.TOPIC }
        return "unknown" to AttributionSource.ABSENT
    }

    /**
     * `openbank.audit.attribution.missing{source_service,provenance}` — rows whose producing
     * service was not stated by the producer (#3994).
     *
     * Counted for TOPIC as well as ABSENT, not only the sentinel. A topic-derived row is a
     * correctly attributed row AND an outstanding producer gap; folding the two together would
     * make the gap disappear from the dashboard the moment this fix ships, which is precisely the
     * kind of silence that let the original defect run to 76%.
     */
    private fun countMissingAttribution(sourceService: String, provenance: AttributionSource) {
        if (!::meterRegistry.isInitialized) return
        meterRegistry.counter(
            "openbank.audit.attribution.missing",
            "source_service",
            sourceService,
            "provenance",
            provenance.name,
        ).increment()
    }

    /**
     * The producer's own event time, or null when the payload does not carry one (#3883).
     *
     * `occurredAt` is the fleet's canonical key — it is declared on
     * `com.openbank.libs.domain.event.DomainEvent`, so every Jackson-of-domain-event producer
     * lands on it. This reads that key and ONLY that key: a second accepted spelling would be a
     * second silent path, and a silent path is exactly how the gap below survived unnoticed.
     *
     * Two ways to have no event time, both previously invisible:
     *  - the key is absent. 7 of the 21 consumed topics are in this state today (clearing,
     *    dispute, statement, sanctions, six of lending's payloads, sepa-payment's Temporal path,
     *    and document-service which names it `at`). The old code substituted `Instant.now(clock)`
     *    and the row then asserted, indistinguishably from a real one, that the operation happened
     *    when the consumer got round to it. Under consumer lag or a replay that is arbitrarily
     *    wrong, and it is the GDPR Art. 30 "when" dimension and DORA Art. 17 evidence.
     *  - the key is present but unparseable. That threw out of the constructor into consume()'s
     *    catch, so the WHOLE audit entry was dropped — one malformed field lost the record of the
     *    operation entirely. It is now an INGEST-sourced row: a degraded entry beats no entry.
     */
    private fun eventTime(node: JsonNode): Instant? {
        val raw = node.textOrNull("occurredAt") ?: return null
        return runCatching { Instant.parse(raw) }.getOrElse {
            log.warnf("Unparseable occurredAt %s; recording ingest time instead", raw.take(MAX_LOGGED_RAW_TIME_CHARS))
            null
        }
    }

    // Ordered (field name -> aggregate type) fallback chain, first match wins. One shared table
    // backs both inferAggregateId/inferAggregateType instead of two parallel chains that drift —
    // add a new topic's identifying field here rather than duplicating a branch in both functions
    // (kept the two in sync by hand across #996 rounds 1-3 until this got too complex; also fixes
    // a latent inconsistency where kycCaseId had a type but no matching id-extraction branch).
    // "incident" (security.ict.incident) is the one exception: its id is nested, not a top-level
    // field, so it is handled separately before this table.
    private val aggregateFields = listOf(
        "accountId" to "ACCOUNT",
        "partyId" to "PARTY",
        "transactionId" to "TRANSACTION",
        "consentId" to "CONSENT",
        "kycCaseId" to "KYC_CASE",
        "batchId" to "CLEARING_BATCH",
        "itemId" to "CLEARING_ITEM",
        "cardId" to "CARD",
        "disputeId" to "DISPUTE",
        "paymentId" to "PAYMENT",
        "loanApplicationId" to "LOAN_APPLICATION",
        "loanId" to "LOAN",
        "documentId" to "DOCUMENT",
        "ceremonyId" to "SIGNATURE_CEREMONY",
        "conversionId" to "FX_CONVERSION",
        "swiftMessageId" to "SWIFT_MESSAGE",
        "id" to "SANCTIONS_CHECK",
    )

    // Both halves test the SAME predicate ([textOrNull], not `has`) on purpose. `has` is true for a
    // field explicitly set to JSON null, so the old pair disagreed on exactly that input: the type
    // side claimed the aggregate ("accountId": null -> ACCOUNT) while the id side produced the
    // string "null" — a typed aggregate pointing at an id that identifies nothing. Keeping the
    // predicate identical is what makes the two sides answer about the same field (#3994).
    private fun inferAggregateId(node: JsonNode): String {
        node["incident"]?.textOrNull("id")?.let { return it }
        for ((field, _) in aggregateFields) {
            node.textOrNull(field)?.let { return it }
        }
        return "unknown"
    }

    private fun inferAggregateType(node: JsonNode): String {
        if (node["incident"]?.textOrNull("id") != null) return "ICT_INCIDENT"
        for ((field, type) in aggregateFields) {
            if (node.textOrNull(field) != null) return type
        }
        return "UNKNOWN"
    }

    private companion object {
        /** Cap on the producer-supplied value echoed into the warning — it is untrusted input. */
        const val MAX_LOGGED_RAW_TIME_CHARS = 64
    }
}

/**
 * One string field of a producer's payload, or null when the producer did not supply one (#3994).
 *
 * **Why this exists rather than `node[field]?.asText()`.** Jackson's `asText()` on a `NullNode`
 * returns the four-character STRING `"null"`, so `{"actorId": null}` — a producer explicitly
 * saying it has no actor — stored `actorId = "null"` as though that were somebody. Measured on the
 * live audit database: **7 rows carry `actor_id = 'null'`**, all `TransactionInitiated`, all
 * money-path. That is worse than the NULL it replaces and is why it survived: a NULL actor reads
 * as a known gap and gets counted, whereas `"null"` reads as an attributed row. It is also
 * chain-hashed into `record_hash` (`actorId` is in `chainHash`'s canonical string), returned by
 * the ADR-0226 `findByActorId` cross-channel person query, and served to data subjects by the
 * GDPR Art. 15 access log — so a query for actor `"null"` returns seven real transactions
 * belonging to nobody, and a real actor's own access log is missing them.
 *
 * The trap was already known here — [AuditConsumer]'s `onBehalfOf`/`delegationId` carried a
 * hand-written `takeIf { !it.isNull }` guard and a comment explaining precisely this — but the
 * guard was applied at two of the eleven call sites that needed it. That is the argument for one
 * shared accessor over eleven guards: a guard that has to be remembered per field is a guard that
 * documents the defect at the two places it does not occur.
 *
 * Blank is folded in with null for the same reason: `""` is not an actor, an event type or a
 * service name, and letting it through only moves the empty value one layer downstream.
 *
 * Free-standing rather than a member so it also reads on the nested `incident` node, and so the
 * class stays under detekt's function-count threshold.
 */
private fun JsonNode.textOrNull(field: String): String? =
    this[field]?.takeIf { !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
