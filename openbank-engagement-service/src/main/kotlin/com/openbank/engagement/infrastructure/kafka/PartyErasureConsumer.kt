// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Handles `PARTY_ERASED` (GDPR Art. 17, ADR-0118) — same event, same filter idiom as every other
 * consumer of `openbank.party.events` fleet-wide (card-issuance, kyc, aml's own `PartyEventConsumer`
 * siblings). Materialises the [AdverseState.ERASURE_REQUESTED] half of ADR-0220 D3.5's targeting
 * exclusion. Deliberately never cleared: there is no "un-erase" event anywhere in this fleet —
 * erasure is terminal, so once set this state is permanent for the party.
 *
 * Poison-pill safe: failures are logged and acked.
 */
@ApplicationScoped
class PartyErasureConsumer(private val adverseState: AdverseStateRepository, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(PartyErasureConsumer::class.java)

    @Incoming("party-events-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("eventType").asText() != "PARTY_ERASED") return

            val partyId = node.path("partyId").asText(null)?.let(UUID::fromString) ?: run {
                log.warnf("PARTY_ERASED without valid partyId, skipping: %.300s", payload)
                return
            }
            adverseState.setActive(partyId, AdverseState.ERASURE_REQUESTED, Instant.now())
            log.infof("ADR-0220 D3.5: excluded party %s from targeting (PARTY_ERASED)", partyId)
        } catch (e: Exception) {
            log.errorf(e, "Failed to handle PARTY_ERASED event: %.300s", payload)
        }
    }
}
