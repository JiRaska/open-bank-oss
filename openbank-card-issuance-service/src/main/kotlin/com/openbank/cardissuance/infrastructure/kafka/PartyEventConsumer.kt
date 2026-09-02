// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.cardissuance.application.port.out.CardRepository
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Handles PARTY_ERASED events from party-service (GDPR Art. 17 / ADR-0117):
 * anonymises cardholder PII (name, embossed name, delivery address) for all cards
 * belonging to the party. The card aggregate itself (status, limits, expiry) is retained
 * for fraud / chargeback resolution under AML/banking record-retention obligations.
 *
 * Poison-pill safe: failures are logged and acked.
 */
@ApplicationScoped
class PartyEventConsumer {

    @Inject
    lateinit var cardRepository: CardRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(PartyEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON: %.200s", payload)
            return
        }

        if (node.path("eventType").asText() != "PARTY_ERASED") return

        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull()
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
            return
        }

        // Retried, then RETHROWN for the connector to dead-letter (#5698). A swallowed failure here
        // leaves card PII in place while the log records the erasure as done — a GDPR Art. 17 breach
        // that no metric, dashboard or DLQ would show. anonymizeByPartyId is idempotent.
        EventRetry.withRetry(log, "PARTY_ERASED card anonymisation", partyId) {
            cardRepository.anonymizeByPartyId(partyId)
            log.infof("[party-events-in] GDPR Art. 17: anonymised card PII for party %s", partyId)
        }
    }
}
