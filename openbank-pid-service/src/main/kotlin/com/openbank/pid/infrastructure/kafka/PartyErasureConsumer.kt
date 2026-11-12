// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.pid.application.port.`in`.ErasePartyUseCase
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Handles `PARTY_ERASED` from party-service's `openbank.party.events` (GDPR Art. 17, ADR-0118,
 * #15755) — the same event and filter idiom as kyc, engagement and notification.
 *
 * A MALFORMED event (unparseable, no valid partyId) is unretryable and is logged and acked. A
 * failing erase is a dependency failure, not a bad event: it is retried and then RETHROWN so the
 * connector dead-letters it (`openbank.dlq.pid.party-events-in`, wired in application.yaml and the
 * pid KafkaUser). Acking a failed erasure would leave an encrypted national identifier in place
 * while the log claims otherwise. The erase is idempotent, so retries and redelivery are safe.
 *
 * Never logs the payload beyond a truncated malformed fragment, and never an identifier.
 */
@ApplicationScoped
class PartyErasureConsumer(private val erasure: ErasePartyUseCase, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(PartyErasureConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val partyId = parse(payload) ?: return
        EventRetry.withRetry(log, "PARTY_ERASED identity erasure", partyId) {
            erasure.erase(partyId)
        }
    }

    /** Parsing + routing only: every outcome here is unretryable, so null means "ack and move on". */
    @Suppress("TooGenericExceptionCaught") // whatever Jackson or UUID.fromString throws, a malformed
    // payload is the same unretryable poison pill.
    private fun parse(payload: String): UUID? = try {
        val node = objectMapper.readTree(payload)
        if (node.path("eventType").asText() != "PARTY_ERASED") {
            null
        } else {
            node.path("partyId").asText(null)?.let(UUID::fromString)
                ?: run {
                    log.warn("PARTY_ERASED without a partyId, skipping")
                    null
                }
        }
    } catch (e: Exception) {
        log.errorf(e, "Failed to parse party event (length %d), skipping", payload.length)
        null
    }
}
