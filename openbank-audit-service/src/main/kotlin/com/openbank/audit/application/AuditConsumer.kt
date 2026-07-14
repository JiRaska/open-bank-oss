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
            )
            repo.save(entry)
        } catch (e: Exception) {
            log.errorf(e, "Failed to record audit entry: %s", payload.take(200))
        }
    }

    private fun inferAggregateId(node: JsonNode): String = node["accountId"]?.asText()
        ?: node["partyId"]?.asText()
        ?: node["transactionId"]?.asText()
        ?: node["consentId"]?.asText()
        // clearing.batch.event: publishBatchSettled/publishItemCleared carry batchId/itemId, not a
        // shared aggregate field name (PR #1007).
        ?: node["batchId"]?.asText()
        ?: node["itemId"]?.asText()
        // security.ict.incident: IctIncidentService.publishEvent nests the incident under
        // "incident" rather than a top-level id field (PR #1007).
        ?: node["incident"]?.get("id")?.asText()
        // cards.events (CardStatusChanged has no accountId/partyId), dispute.events,
        // domestic.payment.events + sepa.payment.events, sanctions.screening.event (#996 round 2).
        ?: node["cardId"]?.asText()
        ?: node["disputeId"]?.asText()
        ?: node["paymentId"]?.asText()
        ?: node["id"]?.asText()
        ?: "unknown"

    private fun inferAggregateType(node: JsonNode): String = when {
        node.has("accountId") -> "ACCOUNT"
        node.has("partyId") -> "PARTY"
        node.has("transactionId") -> "TRANSACTION"
        node.has("consentId") -> "CONSENT"
        node.has("kycCaseId") -> "KYC_CASE"
        node.has("batchId") -> "CLEARING_BATCH"
        node.has("itemId") -> "CLEARING_ITEM"
        node.has("incident") -> "ICT_INCIDENT"
        node.has("cardId") -> "CARD"
        node.has("disputeId") -> "DISPUTE"
        node.has("paymentId") -> "PAYMENT"
        node.has("id") -> "SANCTIONS_CHECK"
        else -> "UNKNOWN"
    }
}
