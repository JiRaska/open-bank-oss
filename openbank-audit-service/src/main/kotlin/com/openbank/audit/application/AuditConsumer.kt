// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.domain.model.ActorProvenance
import com.openbank.audit.domain.model.AggregateIdProvenance
import com.openbank.audit.domain.model.AttributionSource
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import com.openbank.audit.infrastructure.persistence.PartyMergeIndexRepository
import com.openbank.libs.domain.identifiers.Ids
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

    // ADR-0179 / issue #1984: the write side of `merged_into` adoption. Optional in tests the
    // same way meterRegistry is below — a unit test that never sets it just skips the index
    // write, it does not crash (most tests never send a PARTY_MERGED payload, but at least one,
    // AuditAttributionTest, deliberately does — for its own unrelated attribution assertion).
    @Inject lateinit var mergeIndex: PartyMergeIndexRepository

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
            persist(payload, addressOf(message))
        } catch (e: Exception) {
            // best-effort: DELIBERATE, and #6209 is where it was decided — this legacy
            // multi-producer channel keeps its historic availability behaviour rather than wedging
            // ~20 producers on one store failure. Stated plainly because the marker suppresses the
            // event-handler-swallow gate (#5698) and the cost is real: a store failure here loses
            // an evidentiary row, and an acked message is indistinguishable from a stored one.
            // The strict path is AgentAuditConsumer — it acknowledges only a successful durable
            // write, so a D5 provenance store failure is retried by Kafka rather than lost. Any
            // producer that cannot tolerate this trade belongs on that consumer, not this one.
            log.errorf(e, "Failed to record audit entry: %s", payload.take(200))
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
            persist(payload, address)
        } catch (e: Exception) {
            // best-effort: the same deliberate #6209 trade as the @Incoming overload above, and for
            // the same channel — this is the no-broker-metadata entry point into it. See there for
            // why, and for the strict alternative (AgentAuditConsumer).
            log.errorf(e, "Failed to record audit entry: %s", payload.take(200))
        }
    }

    /**
     * Writes an audit event and propagates a failure to the caller. The dedicated agent-provenance
     * consumer uses this method before ACKing, while the legacy mixed stream keeps [consume]'s
     * compatibility behaviour.
     */
    suspend fun persist(payload: String, address: EventAddress = EventAddress.NONE) {
        val node: JsonNode = objectMapper.readTree(payload)
        val eventTime = eventTime(node)
        val resolvedSource = resolveSourceService(node, address)
        val actor = resolveActor(node)
        val resolvedAggregateId = resolveAggregateId(node)
        val entry = AuditEntry(
            // A producer event id makes at-least-once Kafka delivery idempotent. Legacy
            // producers without one retain the previous random entry id behaviour.
            id = node.textOrNull("eventId")?.let(UUID::fromString) ?: Ids.newId(),
            // sepa.instant.events (KafkaSctInstEventPublisher) names its discriminator "type",
            // not "eventType" — the only #996-consumed producer that does so.
            // `ce-type` is the outbox event type, and it is the LAST resort before the
            // sentinel: it is the producer's own value, carried by the transport rather than
            // the body, so it is a recovery of a fact and not an inference (#3994).
            eventType = node.textOrNull("eventType")
                ?: node.textOrNull("type")
                ?: address.ceType
                ?: "UNKNOWN",
            // Uppercased (issue #4553's pattern, confirmed live here 2026-08-13): a producer's
            // own "aggregateType" field survives verbatim while inferAggregateType's table below
            // is all uppercase, so the column records WHICH resolution path fired, not what the
            // aggregate is. Measured on the live audit_entries table before this fix:
            // ACCOUNT 656 / Account 126, Transaction 193 with ZERO uppercase TRANSACTION rows,
            // Consent 11 with ZERO uppercase CONSENT rows. AuditConsumer's own KDoc already
            // claims "the same fix as the analytics sink's #2598" for the attribution gap; this
            // is the casing gap #2598's fix didn't cover, in the SAME shape #4553/#4576 found
            // and fixed in openbank-analytics-sink. Rows already written keep their spelling —
            // this stops the split growing, it does not backfill the 10-year tamper-evident
            // audit trail (ADR-0023-equivalent reasoning: a mutation of the log of record needs
            // its own decision, not a drive-by fix here).
            aggregateType = (node.textOrNull("aggregateType") ?: inferAggregateType(node)).uppercase(),
            // Envelope first, mirroring aggregateType directly above (#6318). The producer's
            // own `aggregateId` is a DECLARATION of which resource the event is about;
            // inferAggregateId is an INFERENCE that takes the first business id it recognises,
            // which is not the same question. Reading the type from the producer and the id from
            // the chain let one row assert both at once: measured on the live table before this
            // fix, `JournalPosted` rows carried aggregate_type=JOURNALENTRY (declared) with
            // aggregate_id = the transactionId (inferred) — a type and an id naming different
            // objects, and `AccountCreated`/`ConsentGranted` were keyed by partyId the same way.
            //
            // Blast radius, established per producer rather than assumed (the issue's condition
            // for this change). A grep for the literal key finds ONE topic; the true count is
            // SEVEN, because `com.openbank.libs.domain.event.DomainEvent` declares
            // `abstract val aggregateId: UUID`, so every Jackson-serialised subclass puts the key
            // on the wire with no literal anywhere to grep. On the live table every row whose
            // envelope named the resource stored something else instead — most as the "unknown"
            // sentinel, the rest as a different resource's id — while rows whose envelope stayed
            // silent were almost never "unknown". So this changes the stored id only where the
            // producer had already said what it should be.
            //
            // Rows already written keep their value: audit_entries is append-only at the DB
            // (`no_update_audit` is DO INSTEAD NOTHING, so an UPDATE affects zero rows and
            // REPORTS SUCCESS) and aggregate_id is chain-hashed into record_hash. This stops the
            // gap growing; a backfill of the tamper-evident log needs its own decision, not a
            // drive-by migration here — the same reasoning #4553 applied to the casing split.
            aggregateId = resolvedAggregateId.first,
            actorId = actor.first,
            actorType = actor.second,
            payload = payload,
            sourceService = resolvedSource.first,
            sourceServiceSource = resolvedSource.second,
            correlationId = node.textOrNull("correlationId"),
            occurredAt = eventTime ?: Instant.now(clock),
            recordedAt = Instant.now(clock),
            occurredAtSource = if (eventTime != null) OccurredAtSource.EVENT else OccurredAtSource.INGEST,
            // Namespaced by source topic (issue #4660), not the bare producer value. A fleet
            // sweep after #4553 found the bare JSON key "channel" independently populated by
            // THREE producers with no shared vocabulary: AuditChannel (ADR-0226,
            // ingress — this field's original intent, "ui"/"mcp"/"api"), OnboardingChannel
            // (party-service, via RelationshipAddedEvent on openbank.party.events — "API"
            // collides with AuditChannel's "api" on both case and meaning) and ComplaintChannel
            // (dispute-service, via openbank.dispute.events — the only one confirmed live
            // before this fix, all rows spelled "APP"). Storing the bare value made the column
            // ungroupable: a caller reading "API" could not tell an onboarding channel from an
            // ingress one. See resolveChannel() for the mapping.
            channel = resolveChannel(node.textOrNull("channel"), address.topic),
            actChain = node["actChain"]?.takeIf { it.isArray }?.map { it.asText() } ?: emptyList(),
            sessionId = node.textOrNull("sessionId"),
            // ADR-0232 D5: a delegated action names the grantor it was taken on behalf of and
            // the grant that permitted it. customer-edge flattens its audit details into the
            // event JSON, so both arrive as top-level fields. Absent = a direct action.
            onBehalfOf = node.textOrNull("onBehalfOf"),
            delegationId = node.textOrNull("delegationId"),
        )
        if (eventTime == null) countMissingEventTime(entry.sourceService)
        if (::meterRegistry.isInitialized) {
            meterRegistry.countActorProvenance(
                entry.sourceService,
                actorProvenance(entry.actorId, entry.actorType),
            )
            // Folded into the existing guard rather than given its own `if`: detekt's
            // CyclomaticComplexMethod fires AT the threshold (15), and persist() was already there.
            meterRegistry.countAggregateIdProvenance(entry.sourceService, resolvedAggregateId.second)
        }
        if (entry.sourceServiceSource != AttributionSource.EVENT) {
            countMissingAttribution(entry.sourceService, entry.sourceServiceSource)
        }
        repo.save(entry)
        if (entry.eventType == "PARTY_MERGED" && ::mergeIndex.isInitialized) {
            recordPartyMergeIndex(mergeIndex, log, node, entry.occurredAt)
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
     *  - the key is absent. The old code substituted `Instant.now(clock)` and the row then
     *    asserted, indistinguishably from a real one, that the operation happened when the consumer
     *    got round to it. Under consumer lag or a replay that is arbitrarily wrong, and it is the
     *    GDPR Art. 30 "when" dimension and DORA Art. 17 evidence.
     *
     *    **This paragraph used to name the topics in that state, and the list went stale within
     *    hours of being written** (#8352). It said "7 of the 21 consumed topics — clearing,
     *    dispute, statement, sanctions, six of lending's payloads, sepa-payment's Temporal path,
     *    and document-service which names it `at`", and by the time anyone read it the #3914/#3926
     *    sweep had fixed every one of those except a `dispute.opened` builder that had landed three
     *    hours earlier and so was invisible to that sweep's branch. Meanwhile the subscription grew
     *    from 21 topics to 27 and the list could not know. A comment naming other services' current
     *    payload shapes is a claim with a shelf life and nothing re-checks it — the same trap this
     *    repo already records for comments asserting current CONFIGURATION. So this one now names
     *    the MECHANISM only. What the state is today is answered by things that cannot go stale:
     *    `openbank.audit.event.time.missing{source_service}` as a series, `occurred_at_source` per
     *    row, and the per-topic disposition in `openbank-audit-service/CLAUDE.md`.
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

    private companion object {
        /** Cap on the producer-supplied value echoed into the warning — it is untrusted input. */
        const val MAX_LOGGED_RAW_TIME_CHARS = 64
    }
}

// Ordered (field name -> aggregate type) fallback chain, first match wins. One shared table backs
// both inferAggregateId/inferAggregateType instead of two parallel chains that drift — add a new
// topic's identifying field here rather than duplicating a branch in both functions (kept the two
// in sync by hand across #996 rounds 1-3 until this got too complex; also fixes a latent
// inconsistency where kycCaseId had a type but no matching id-extraction branch). "incident"
// (security.ict.incident) is the one exception: its id is nested, not a top-level field, so it is
// handled separately before this table. Top-level, not a class member: it needs no instance
// state, and `AuditConsumer` was already at detekt's TooManyFunctions threshold.
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
/**
 * The resource the event is about, and who decided it (#6318).
 *
 * Ordered strongest claim first, exactly as [AuditConsumer.resolveSourceService] does for the
 * producing service:
 *  1. the producer's own top-level `aggregateId` — [AggregateIdProvenance.DECLARED];
 *  2. the ordered business-id chain below — [AggregateIdProvenance.INFERRED];
 *  3. neither — the `"unknown"` sentinel, [AggregateIdProvenance.ABSENT].
 *
 * The pair is returned together so a caller cannot take the value without the provenance, which
 * is what stops an inferred id being counted as a declared one.
 *
 * Uses [textOrNull], so an explicit `"aggregateId": null` or `""` falls through to inference
 * rather than storing the four-character string `"null"` as a resource id — the same trap that
 * put seven money-path rows on the live table with `actor_id = 'null'`.
 *
 * One producer needs naming rather than leaving to a reader: `openbank.agent.audit.events` sets
 * `aggregateId = event.resourceId ?: event.actorId`, so an agent action with no resource now
 * stores the ACTOR's id as the aggregate instead of `"unknown"`. That is a producer-side defect
 * this consumer cannot see or correct — the envelope is a declaration and there is nothing in it
 * to distinguish the fallback — and it is filed separately. It is not an argument for
 * inference-first: inference stores `"unknown"` for that same payload, so the honest-looking
 * value today is an accident of the chain not recognising anything, not a judgement about the
 * producer.
 */
private fun resolveAggregateId(node: JsonNode): Pair<String, AggregateIdProvenance> {
    node.textOrNull("aggregateId")?.let { return it to AggregateIdProvenance.DECLARED }
    val inferred = inferAggregateId(node)
    return if (inferred == UNKNOWN_AGGREGATE_ID) {
        inferred to AggregateIdProvenance.ABSENT
    } else {
        inferred to AggregateIdProvenance.INFERRED
    }
}

/**
 * `openbank.audit.aggregate.id.provenance{source_service,provenance}` — which of the three paths
 * above decided each row's `aggregate_id` (#6318).
 *
 * Counted for DECLARED as well as the two weaker outcomes, not only on failure: a counter that
 * increments only when something is missing cannot distinguish "no gaps" from "no traffic", and
 * the ratio this exists to expose needs its denominator. Tag cardinality is bounded by the fixed
 * subscribed-topic service list times three enum values.
 *
 * The alertable state is a producer that STOPS declaring — DECLARED falling to INFERRED for a
 * source_service that used to be all-DECLARED is a regression that writes permanently wrong ids
 * into an append-only table, and before this counter nothing anywhere would have said so.
 */
internal fun MeterRegistry.countAggregateIdProvenance(sourceService: String, provenance: AggregateIdProvenance) {
    counter(
        "openbank.audit.aggregate.id.provenance",
        "source_service",
        sourceService,
        "provenance",
        provenance.name,
    ).increment()
}

/** The sentinel stored when neither the producer nor the inference chain names a resource. */
private const val UNKNOWN_AGGREGATE_ID = "unknown"

private fun inferAggregateId(node: JsonNode): String {
    node["incident"]?.textOrNull("id")?.let { return it }
    for ((field, _) in aggregateFields) {
        node.textOrNull(field)?.let { return it }
    }
    return UNKNOWN_AGGREGATE_ID
}

private fun inferAggregateType(node: JsonNode): String {
    if (node["incident"]?.textOrNull("id") != null) return "ICT_INCIDENT"
    for ((field, type) in aggregateFields) {
        if (node.textOrNull(field) != null) return type
    }
    return "UNKNOWN"
}

/** Matches AuditRepository's `@Column(name = "channel", length = 32)` (V14, issue #4660). */
private const val MAX_CHANNEL_CHARS = 32

/**
 * Namespaces a raw "channel" value by the topic it arrived on (issue #4660), rather than
 * trusting the bare value's vocabulary. Keyed on the TOPIC, not on inferred aggregate type or
 * event shape: the topic is the one signal every producer already agrees on (it is how they
 * get subscribed to in the first place), so this needs no per-producer parsing and stays
 * correct for a producer neither of us has read the code of yet — an unrecognised topic still
 * gets its own disjoint namespace ("$topic:$raw"), it just isn't given a friendly name.
 *
 * The two known mappings exist only to keep the common rows short and readable within the
 * VARCHAR(32) column (V14) — "onboarding:MOBILE_APP" (22 chars) fits; a full topic name would
 * not ("openbank.party.events:MOBILE_APP" is 33). A top-level function, not a class member: it
 * needs no instance state, and `AuditConsumer` was already at detekt's TooManyFunctions threshold.
 */
private fun resolveChannel(raw: String?, topic: String?): String? {
    if (raw == null) return null
    val namespace = when (topic) {
        "openbank.party.events" -> "onboarding"
        "openbank.dispute.events" -> "complaint"
        // Not "openbank.customer.audit" -> "ingress": that topic exists and is subscribed, but
        // its one real publisher (EdgeAuditPublisher) sets no "channel" field today, and
        // AuditChannel (ADR-0226's ui/mcp/api) has no live Kafka path at all yet — McpCallAuditor
        // only reaches LoggingAuditEventPublisher. Naming a topic for a wiring that does not
        // exist would be a guess about where it eventually lands, not a fact. The catch-all
        // below already gives it a disjoint namespace the day it does.
        else -> topic ?: "unscoped"
    }
    return "$namespace:$raw".take(MAX_CHANNEL_CHARS)
}

/**
 * `openbank.audit.actor.missing{source_service,provenance}` — the third leg of #3994, and the
 * only one of the three that had no signal at all.
 *
 * The issue asked for three things in increasing cost: make the gap loud, fix the producers,
 * and decide whether an actor can be REQUIRED. This is the first, for the actor dimension:
 * `openbank.audit.event.time.missing` and `openbank.audit.attribution.missing` already exist,
 * and the actor gap — 75% of the live trail, the larger of the two halves in this issue's
 * title — was observable only by hand-running a `GROUP BY` against the audit database.
 *
 * **What the counter can and cannot fix, and how that was established.** Asking the live
 * database which actor-ish keys appear in actor-less payloads is the obvious probe and it
 * gives the WRONG answer:
 *
 * ```
 * SELECT DISTINCT k FROM audit_entries, jsonb_object_keys(payload::jsonb) k
 *  WHERE actor_id IS NULL AND (k ILIKE '%by%' OR k ILIKE '%actor%' OR ...);
 *   -> initiatedByPartyId, reviewedBy      (and reviewedBy is JSON-null on all 53 rows)
 * ```
 *
 * That reads as "no actor is recoverable, every gap is a producer omission" — and it is a
 * measurement of sandbox TRAFFIC, not of the wire contract. `openbank.cards.events`,
 * `openbank.lending.events` and account-service's savings path have no rows here at all, and
 * sanctions' `reviewedBy` is null only because no manual review has run. Enumerating the
 * producers' serialised TYPES instead found three actor spellings genuinely on the wire and
 * unread — `reviewedBy`, `changedBy`, `actorKind` — all three in data classes where the JSON
 * key exists only as a Kotlin property name, so no grep for a quoted field name would have
 * found them either. Those are recovered above.
 *
 * What remains after that is a genuine producer-side omission — the actor is known to the
 * service and never reaches the wire (consent's `createdBy`/`revokedBy`, dispute's
 * `resolvedBy`, domestic-payment's command `actorId` from the JWT, statement's
 * `period.restated.v1`). This consumer must not paper over those: `actor_id` is chain-hashed
 * into `record_hash`, so a fabricated actor is not a lesser evil than an honest NULL. This
 * counter is what makes each producer's fix — or regression — visible without another manual
 * query.
 *
 * Counted for DECLARED too, not only the two absences: a ratio needs its denominator, and a
 * counter that only ever increments on failure cannot distinguish "no gaps" from "no traffic"
 * — the exact shape of the zero-denials-from-an-idle-service trap. Tag cardinality is bounded
 * by the fixed 21-topic service list times three enum values.
 */
internal fun MeterRegistry.countActorProvenance(sourceService: String, provenance: ActorProvenance) {
    counter(
        "openbank.audit.actor.missing",
        "source_service",
        sourceService,
        "provenance",
        provenance.name,
    ).increment()
}

/**
 * The actor identity and kind a producer put on the wire: `(actorId, actorType)` (#3994).
 *
 * **The first three id spellings are unchanged and stay FIRST**, so no row that is attributed
 * today changes actor — a fix that improves 1359 unattributed rows by silently moving the 425
 * already-correct ones is a regression, not a fix.
 *
 * The last two are additive recoveries: real actor identities that were already on the wire and
 * that this consumer simply was not reading, so they landed as NULL in a column chain-hashed into
 * `record_hash` and served to data subjects by the GDPR Art. 15 access log.
 *
 *  - `reviewedBy` — sanctions.screening.event serialises the `SanctionsCheck` aggregate whole, so
 *    the four-eyes manual-review identity (the highest-value actor in the fleet) rides as a Kotlin
 *    property name with no string literal anywhere in the producer to grep for.
 *  - `changedBy` — cards.events: `CardStatusChanged`, `CardLimitsChanged` and `CardControlsChanged`
 *    all carry it; only `CardIssued` omits it.
 *  - `actorKind` — lending's transition events emit `actorId` AND `actorKind`, so the id was
 *    already caught while the TYPE beside it was dropped. The row named the actor but could not
 *    say whether it was a human or the automated policy engine, which is exactly what a four-eyes
 *    or DORA Art. 17 reconstruction asks of a credit decision.
 *
 * Deliberately NOT recovered, though both are actor-ish and tempting: `partyId` (a data SUBJECT on
 * most topics, an actor only on dispute/fx — a per-topic rule, not a spelling) and
 * `delegatePartyId` (a delegate belongs in ADR-0232's `onBehalfOf`/`delegationId` pair, which this
 * consumer already reads separately). Guessing either would write a confident wrong actor into a
 * tamper-evident record — the same failure mode `TopicAttribution` exists to avoid.
 *
 * Free-standing to keep [AuditConsumer] under detekt's function-count threshold, which fires AT
 * the threshold and not above it.
 */
private fun resolveActor(node: JsonNode): Pair<String?, String?> = Pair(
    node.textOrNull("requestedBy")
        ?: node.textOrNull("actorId")
        // transaction.initiated events carry the customer identity here (ADR-0021).
        ?: node.textOrNull("initiatedByPartyId")
        ?: node.textOrNull("reviewedBy")
        ?: node.textOrNull("changedBy"),
    node.textOrNull("actorType") ?: node.textOrNull("actorKind"),
)

/**
 * Classifies one row's actor claim for the [AuditConsumer.countActorProvenance] counter (#3994).
 *
 * Reads the RESOLVED entry fields rather than the payload, so it classifies exactly what was
 * stored — a second pass over the JSON could disagree with the row it is supposed to describe,
 * which is the drift that made `inferAggregateId`/`inferAggregateType` contradict each other.
 *
 * Free-standing to keep [AuditConsumer] under detekt's function-count threshold.
 */
internal fun actorProvenance(actorId: String?, actorType: String?): ActorProvenance = when {
    actorId != null -> ActorProvenance.DECLARED
    actorType != null -> ActorProvenance.SYSTEM
    else -> ActorProvenance.ABSENT
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

/**
 * ADR-0179 / issue #1984: records the `retired -> survivor` edge from a `PARTY_MERGED` event
 * (`PartyEvents.merged`'s exact field names: `partyId` is the retired duplicate,
 * `mergedIntoPartyId` the survivor) so [AuditRepository.findByAggregateId] can follow it at read
 * time — `audit_entries` itself cannot be rewritten (V2's append-only RULEs; see V15's migration
 * comment for why this is a read-time fix and not a write-time one).
 *
 * Free-standing, not a member, for the same reason as [resolveActor]/[resolveChannel]:
 * [AuditConsumer] is already at detekt's function-count threshold, which fires AT the threshold.
 *
 * Its own try/catch, deliberately separate from the one around [AuditConsumer.consume]'s call
 * site: by the time this runs, the audit row FOR the `PARTY_MERGED` event itself is already
 * saved. A failure here must not be logged as "failed to record audit entry" (untrue — it did)
 * and must not stop the message ack; it only means one history query stays retired-id-only until
 * the next `PARTY_MERGED` delivery is processed, not that the merge went unrecorded as an event.
 */
// Deliberately broad: a DB hiccup here must never propagate to consume()'s own catch and be
// misreported as "failed to record audit entry" — the audit row is already safely saved by then.
@Suppress("TooGenericExceptionCaught")
private suspend fun recordPartyMergeIndex(
    mergeIndex: PartyMergeIndexRepository,
    log: Logger,
    node: JsonNode,
    occurredAt: Instant,
) {
    val retired = node.textOrNull("partyId")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
    val survivor = node.textOrNull("mergedIntoPartyId")
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return
    try {
        mergeIndex.recordMerge(retired, survivor, occurredAt)
    } catch (e: Exception) {
        log.errorf(e, "Failed to record party-merge index entry for %s -> %s", retired, survivor)
    }
}
