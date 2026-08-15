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
 * Consumes `openbank-fraud-service`'s `fraud.hold_changed` and materialises the
 * [AdverseState.FRAUD_HOLD] half of ADR-0220 D3.5's targeting exclusion — the last of the four
 * `AdverseState` values to get a real signal (issue #2749). `active` toggles the state on or off:
 * unlike [PartyErasureConsumer]'s terminal ERASURE_REQUESTED, a fraud hold auto-expires on
 * fraud-service's side (its own scheduled sweep, no manual clear), so this consumer must honour
 * `active: false` and actually clear the state rather than only ever setting it.
 *
 * Poison-pill safe: parse/handle failures are logged and swallowed so one bad event cannot wedge
 * the consumer group — fraud-service's outbox remains the source of truth and can be replayed.
 */
@ApplicationScoped
class FraudHoldEventConsumer(
    private val adverseState: AdverseStateRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(FraudHoldEventConsumer::class.java)

    @Incoming("fraud-hold-events-in")
    @Suppress("TooGenericExceptionCaught")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            val partyId = node.path("partyId").asText(null)?.let(UUID::fromString) ?: run {
                log.warnf("fraud.hold_changed missing/unparseable partyId, skipping: %.300s", payload)
                return
            }
            val active = node.path("active").asBoolean(false)
            if (active) {
                adverseState.setActive(partyId, AdverseState.FRAUD_HOLD, Instant.now())
            } else {
                adverseState.clearActive(partyId, AdverseState.FRAUD_HOLD)
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to handle fraud.hold_changed event: %.300s", payload)
        }
    }
}
