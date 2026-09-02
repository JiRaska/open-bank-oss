// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import com.openbank.libs.messaging.EventRetry
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
 * Poison-pill safe for a MALFORMED event: an unparseable payload or a missing partyId is logged and
 * acked, because replaying it fails identically forever. A failure of the `adverseState` write is
 * the opposite case and is retried, then rethrown for the DLQ — see [withBoundedRetry] (#5698).
 * There is no compensating path here at all: erasure is terminal and never re-derived, so a write
 * that was acked without happening leaves the party permanently targetable with nothing to notice.
 */
@ApplicationScoped
class PartyErasureConsumer(private val adverseState: AdverseStateRepository, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(PartyErasureConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val partyId = parse(payload) ?: return
        EventRetry.withRetry(log, "erasure exclusion for party $partyId", null) {
            adverseState.setActive(partyId, AdverseState.ERASURE_REQUESTED, Instant.now())
            log.infof("ADR-0220 D3.5: excluded party %s from targeting (PARTY_ERASED)", partyId)
        }
    }

    /** Parsing + routing only — every outcome here is unretryable, so a null means "ack and move on". */
    @Suppress("TooGenericExceptionCaught") // whatever Jackson or UUID.fromString throws, a malformed
    // payload is the same unretryable poison pill.
    private fun parse(payload: String): UUID? =
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("eventType").asText() != "PARTY_ERASED") {
                null
            } else {
                node.path("partyId").asText(null)?.let(UUID::fromString)
                    ?: run {
                        log.warnf("PARTY_ERASED without valid partyId, skipping: %.300s", payload)
                        null
                    }
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to parse PARTY_ERASED event: %.300s", payload)
            null
        }
}
