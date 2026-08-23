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
 * Consumes `openbank-fraud-service`'s `fraud.hold_changed` and materialises the
 * [AdverseState.FRAUD_HOLD] half of ADR-0220 D3.5's targeting exclusion — the last of the four
 * `AdverseState` values to get a real signal (issue #2749). `active` toggles the state on or off:
 * unlike [PartyErasureConsumer]'s terminal ERASURE_REQUESTED, a fraud hold auto-expires on
 * fraud-service's side (its own scheduled sweep, no manual clear), so this consumer must honour
 * `active: false` and actually clear the state rather than only ever setting it.
 *
 * Poison-pill safe for a MALFORMED event: an unparseable payload or a missing partyId is logged and
 * acked, because replaying it fails identically forever. A failure of the `adverseState` write is
 * the opposite case and is retried, then rethrown for the DLQ — see [withBoundedRetry] (#5698).
 * Losing this signal silently is the worst of the four: it is the fraud-hold marketing exclusion.
 */
@ApplicationScoped
class FraudHoldEventConsumer(
    private val adverseState: AdverseStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(FraudHoldEventConsumer::class.java)

    @Incoming("fraud-hold-events-in")
    suspend fun consume(payload: String) {
        val signal = parse(payload) ?: return
        EventRetry.withRetry(log, "fraud-hold exclusion for party ${signal.partyId} (active=${signal.active})", null) {
            if (signal.active) {
                adverseState.setActive(signal.partyId, AdverseState.FRAUD_HOLD, Instant.now())
            } else {
                adverseState.clearActive(signal.partyId, AdverseState.FRAUD_HOLD)
            }
        }
    }

    /** Parsing only — every outcome here is unretryable, so a null means "ack and move on". */
    @Suppress("TooGenericExceptionCaught") // whatever Jackson or UUID.fromString throws, a malformed
    // payload is the same unretryable poison pill.
    private fun parse(payload: String): FraudHoldSignal? =
        try {
            val node = objectMapper.readTree(payload)
            val partyId = node.path("partyId").asText(null)?.let(UUID::fromString)
            if (partyId == null) {
                log.warnf("fraud.hold_changed missing/unparseable partyId, skipping: %.300s", payload)
                null
            } else {
                FraudHoldSignal(partyId, node.path("active").asBoolean(false))
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to parse fraud.hold_changed event: %.300s", payload)
            null
        }

    private data class FraudHoldSignal(val partyId: UUID, val active: Boolean)
}
