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
 * Consumes `openbank-dispute-service`'s `openbank.dispute.events` and materialises the
 * [AdverseState.DISPUTE_OPENED] quarter of ADR-0220 D1/D3.5's targeting exclusion — the last of
 * the four `AdverseState` values to get a real signal (issue #4262, after #4252 wired FRAUD_HOLD).
 *
 * The topic is SHARED across every dispute lifecycle event (`dispute.opened`, `dispute.resolved`,
 * `dispute.remediation_requested`), so this consumer must filter on `eventType` — the same idiom
 * [PartyErasureConsumer] uses on `openbank.party.events`, and unlike [FraudHoldEventConsumer]
 * whose topic carries exactly one event type. The two types handled here bracket the window
 * during which a customer is in dispute: `dispute.opened` applies the exclusion,
 * `dispute.resolved` lifts it. That pairing is why dispute-service carries `partyId` on BOTH
 * (see `DisputeService.openedOutboxMessage`/`resolvedOutboxMessage`); without the resolved half an
 * exclusion applied on open would never lift.
 *
 * Known cardinality limitation, deliberate and shared with every other signal here:
 * `party_adverse_state` is keyed by (party, state) with no per-dispute reference, so a party with
 * two concurrent disputes has the exclusion lifted by whichever resolves first. Erring towards
 * fewer suppressed marketing surfaces rather than towards a state that can never be cleared, and
 * matching the existing FRAUD_HOLD/ARREARS shape rather than introducing a second one.
 *
 * Poison-pill safe for a MALFORMED event: an unparseable payload or a missing partyId is logged and
 * acked, because replaying it fails identically forever. A failure of the `adverseState` write is
 * the opposite case and is retried, then rethrown for the DLQ — see [withBoundedRetry] (#5698).
 */
@ApplicationScoped
class DisputeOpenedEventConsumer(
    private val adverseState: AdverseStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(DisputeOpenedEventConsumer::class.java)

    @Incoming("dispute-events-in")
    suspend fun consume(payload: String) {
        val signal = parse(payload) ?: return
        EventRetry.withRetry(log, "dispute exclusion for party ${signal.partyId} (${signal.eventType})", null) {
            if (signal.opened) {
                adverseState.setActive(signal.partyId, AdverseState.DISPUTE_OPENED, Instant.now())
                log.infof("ADR-0220 D3.5: excluded party %s from targeting (dispute.opened)", signal.partyId)
            } else {
                adverseState.clearActive(signal.partyId, AdverseState.DISPUTE_OPENED)
                log.infof(
                    "ADR-0220 D3.5: lifted dispute exclusion for party %s (dispute.resolved)",
                    signal.partyId,
                )
            }
        }
    }

    /** Parsing + routing only — every outcome here is unretryable, so a null means "ack and move on". */
    @Suppress("TooGenericExceptionCaught") // whatever Jackson or UUID.fromString throws, a malformed
    // payload is the same unretryable poison pill.
    private fun parse(payload: String): DisputeSignal? =
        try {
            val node = objectMapper.readTree(payload)
            val eventType = node.path("eventType").asText()
            if (eventType != EVENT_OPENED && eventType != EVENT_RESOLVED) {
                null
            } else {
                val partyId = node.path("partyId").asText(null)?.let(UUID::fromString)
                if (partyId == null) {
                    log.warnf("%s missing/unparseable partyId, skipping: %.300s", eventType, payload)
                    null
                } else {
                    DisputeSignal(partyId, eventType)
                }
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to parse dispute lifecycle event: %.300s", payload)
            null
        }

    private data class DisputeSignal(val partyId: UUID, val eventType: String) {
        val opened: Boolean get() = eventType == EVENT_OPENED
    }

    private companion object {
        const val EVENT_OPENED = "dispute.opened"
        const val EVENT_RESOLVED = "dispute.resolved"
    }
}
