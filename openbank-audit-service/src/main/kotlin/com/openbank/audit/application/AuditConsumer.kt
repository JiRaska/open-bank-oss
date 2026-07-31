// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.audit.domain.model.AuditEntry
import com.openbank.audit.infrastructure.persistence.AuditRepository
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

    private val log = Logger.getLogger(AuditConsumer::class.java)

    @Incoming("audit-events-in")
    suspend fun consume(payload: String) {
        try {
            val node: JsonNode = objectMapper.readTree(payload)
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
                occurredAt = node["occurredAt"]?.asText()?.let { Instant.parse(it) } ?: Instant.now(clock),
                recordedAt = Instant.now(clock),
                // ADR-0226: cross-channel dimensions, additive — producers adopt them channel by
                // channel, so absence stays null (unknown), never a guessed default.
                channel = node["channel"]?.asText(),
                actChain = node["actChain"]?.takeIf { it.isArray }?.map { it.asText() } ?: emptyList(),
                sessionId = node["sessionId"]?.asText(),
            )
            repo.save(entry)
        } catch (e: Exception) {
            log.errorf(e, "Failed to record audit entry: %s", payload.take(200))
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
}
