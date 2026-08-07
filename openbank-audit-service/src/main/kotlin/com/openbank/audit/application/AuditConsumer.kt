// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.domain.model.OccurredAtSource
import com.openbank.audit.infrastructure.persistence.AuditRepository
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
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

    @Incoming("audit-events-in")
    suspend fun consume(payload: String) {
        try {
            val node: JsonNode = objectMapper.readTree(payload)
            val eventTime = eventTime(node)
            val entry = AuditEntry(
                id = UUID.randomUUID(),
                // sepa.instant.events (KafkaSctInstEventPublisher) names its discriminator "type",
                // not "eventType" — the only #996-consumed producer that does so.
                eventType = node["eventType"]?.asText() ?: node["type"]?.asText() ?: "UNKNOWN",
                aggregateType = node["aggregateType"]?.asText() ?: inferAggregateType(node),
                aggregateId = inferAggregateId(node),
                actorId = node["requestedBy"]?.asText()
                    ?: node["actorId"]?.asText()
                    // transaction.initiated events carry the customer identity here (ADR-0021).
                    ?: node["initiatedByPartyId"]?.asText(),
                actorType = node["actorType"]?.asText(),
                payload = payload,
                sourceService = node["sourceService"]?.asText() ?: "unknown",
                correlationId = node["correlationId"]?.asText(),
                occurredAt = eventTime ?: Instant.now(clock),
                recordedAt = Instant.now(clock),
                occurredAtSource = if (eventTime != null) OccurredAtSource.EVENT else OccurredAtSource.INGEST,
                // ADR-0226: cross-channel dimensions, additive — producers adopt them channel by
                // channel, so absence stays null (unknown), never a guessed default.
                channel = node["channel"]?.asText(),
                actChain = node["actChain"]?.takeIf { it.isArray }?.map { it.asText() } ?: emptyList(),
                sessionId = node["sessionId"]?.asText(),
                // ADR-0232 D5: a delegated action names the grantor it was taken on behalf of and
                // the grant that permitted it. customer-edge flattens its audit details into the
                // event JSON, so both arrive as top-level fields. Absent = a direct action.
                // (`takeIf { !it.isNull }` because Jackson's asText() on an explicit JSON null
                // yields the STRING "null", which would index a delegated action that is not one.)
                onBehalfOf = node["onBehalfOf"]?.takeIf { !it.isNull }?.asText(),
                delegationId = node["delegationId"]?.takeIf { !it.isNull }?.asText(),
            )
            if (eventTime == null) countMissingEventTime(entry.sourceService)
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
        val raw = node["occurredAt"]?.asText() ?: return null
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

    private fun inferAggregateId(node: JsonNode): String {
        node["incident"]?.get("id")?.asText()?.let { return it }
        for ((field, _) in aggregateFields) {
            node[field]?.asText()?.let { return it }
        }
        return "unknown"
    }

    private fun inferAggregateType(node: JsonNode): String {
        if (node.has("incident")) return "ICT_INCIDENT"
        for ((field, type) in aggregateFields) {
            if (node.has(field)) return type
        }
        return "UNKNOWN"
    }

    private companion object {
        /** Cap on the producer-supplied value echoed into the warning — it is untrusted input. */
        const val MAX_LOGGED_RAW_TIME_CHARS = 64
    }
}
