// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.`in`.MarketingConsentProjectionUseCase
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Projects consent-service's marketing-consent grant/revoke/expiry into `parties.consent_marketing`
 * (ADR-0205 D4). Consumes the WHOLE consent-events topic — every ConsentGranted/Revoked/Expired for
 * every grantee, TPPs included — and filters to [MARKETING_GRANTEE_ID], the fixed internal-grant
 * convention ADR-0205 D3 establishes. Everything else is silently ignored: this is by far the
 * common case (most consent events on this topic are PSD2 TPP consents this service has no
 * business touching), so it is not logged as a skip.
 *
 * Poison-pill safe: parse/handle failures are logged and acked so one bad event cannot wedge the
 * consumer group — consent-service remains the source of truth and can be replayed.
 */
@ApplicationScoped
class MarketingConsentEventConsumer(
    private val projectionUseCase: MarketingConsentProjectionUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = Logger.getLogger(MarketingConsentEventConsumer::class.java)

    companion object {
        const val MARKETING_GRANTEE_ID = "party-service:marketing-comms"
    }

    @Incoming("consent-events-in")
    @Suppress("TooGenericExceptionCaught") // poison-pill safety: catch-all so one malformed or
    // unexpected record can never wedge the consumer group — same convention as
    // KycAmlEventConsumer's identical catch (grandfathered into this service's detekt baseline;
    // this is a new file, so it needs its own suppression rather than relying on that baseline).
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("granteeId").asText() != MARKETING_GRANTEE_ID) return

            val partyId = node.path("partyId").asUuidOrNull() ?: return
            val consentId = node.path("aggregateId").asUuidOrNull() ?: return
            // Falling back to wall-clock time silently would corrupt consent_marketing_updated_at
            // with no way to tell "real event timestamp" from "consumer processed this late/replayed
            // it" apart in an audit trail — log it loudly so the fallback is never mistaken for the
            // happy path.
            val occurredAt = node.path("occurredAt").asInstantOrNull() ?: run {
                log.warnf(
                    "Marketing consent event missing/unparseable occurredAt, falling back to now(): %.300s",
                    payload,
                )
                Instant.now()
            }

            when (node.path("eventType").asText()) {
                "ConsentGranted" -> {
                    projectionUseCase.applyGranted(partyId, consentId, occurredAt)
                    log.infof("Marketing consent GRANTED projected for party %s", partyId)
                }
                "ConsentRevoked", "ConsentExpired" -> {
                    val applied = projectionUseCase.applyRevokedOrExpired(partyId, consentId, occurredAt)
                    log.infof(
                        "Marketing consent %s for party %s: applied=%s",
                        node.path("eventType").asText(),
                        partyId,
                        applied,
                    )
                }
                else -> return // ConsentRejected or any future event type: nothing to project.
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to handle marketing consent event: %.300s", payload)
        }
    }

    private fun JsonNode.asUuidOrNull(): UUID? = runCatching { UUID.fromString(asText()) }.getOrNull()

    private fun JsonNode.asInstantOrNull(): Instant? = runCatching { Instant.parse(asText()) }.getOrNull()
}
