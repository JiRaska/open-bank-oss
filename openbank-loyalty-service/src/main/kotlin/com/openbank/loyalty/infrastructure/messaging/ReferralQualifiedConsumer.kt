// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.messaging

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.loyalty.application.usecase.EarnLeavesUseCase
import com.openbank.loyalty.domain.EarnOutcome
import com.openbank.loyalty.domain.LeafEarnSource
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * ADR-0310 D3 — the first producer of [LeafEarnSource.QualifiedReferral]: a referral that qualified
 * in referral-service earns the REFERRER Lístky. The referee earns nothing here; opening an account
 * is not one of ADR-0282 D3's financial-health signals.
 *
 * **Idempotency is keyed on the invite, not the envelope.** `correlationEventId = inviteId`, so the
 * ledger's `(party, earn source, correlation event)` unique index makes "one invite earns at most
 * once" a database property. Keying on `eventId` would also survive a Kafka redelivery, but not a
 * `Qualified` re-emitted for the same invite under a new event id — and the invite is the
 * achievement being rewarded.
 *
 * [EarnOutcome.Capped] is acked: the annual cap refusing an award is an outcome with its own metric
 * (`openbank_loyalty_earn_capped_total`, counted by the use case), and a retry would be refused
 * identically. A payload without a `referrerPartyId` or `inviteId` is skipped — guessing either
 * would award the wrong party or break the idempotency key. Anything else is retried by
 * [EventRetry] and then rethrown, so the connector's configured `failure-strategy` decides what
 * follows.
 */
@ApplicationScoped
class ReferralQualifiedConsumer(private val earn: EarnLeavesUseCase, private val objectMapper: ObjectMapper) {
    private val log = Logger.getLogger(ReferralQualifiedConsumer::class.java)

    // TooGenericExceptionCaught: an untrusted payload may fail Jackson in any number of ways, and
    // every one of them must be skipped rather than crash the channel (poison-pill safety).
    @Suppress("TooGenericExceptionCaught")
    @Incoming("referral-qualified-in")
    suspend fun consume(payload: String) {
        val node: JsonNode = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "Unparseable referral.qualified event, skipping: %s", payload.take(PAYLOAD_LOG_CHARS))
            return
        }
        if (node["eventType"]?.asText() != EVENT_TYPE) return

        val referrer = node.uuid("referrerPartyId")
        val inviteId = node.uuid("inviteId")
        if (referrer == null || inviteId == null) {
            log.warnf("Qualified missing referrerPartyId/inviteId, skipping: %s", payload.take(PAYLOAD_LOG_CHARS))
            return
        }

        try {
            val outcome = EventRetry.withRetry(
                log,
                "Lístky earn for qualified referral invite $inviteId",
                referrer,
                isRetryable = EventRetry.RETRY_UNLESS_DETERMINISTIC,
            ) {
                earn.earn(referrer, LeafEarnSource.QualifiedReferral, inviteId)
            }
            if (outcome is EarnOutcome.Capped) {
                log.infof(
                    "Qualified referral %s not awarded: annual cap (remaining %s)",
                    inviteId,
                    outcome.remaining.value,
                )
            }
        } catch (e: IllegalStateException) {
            ackDeterministic(e, inviteId)
        } catch (e: IllegalArgumentException) {
            ackDeterministic(e, inviteId)
        }
    }

    private fun ackDeterministic(e: RuntimeException, inviteId: UUID) {
        log.errorf(e, "Lístky earn for qualified referral %s failed deterministically; acked.", inviteId)
    }

    private fun JsonNode.uuid(field: String): UUID? =
        this[field]?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private companion object {
        const val EVENT_TYPE = "Qualified"
        const val PAYLOAD_LOG_CHARS = 200
    }
}
