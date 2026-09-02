// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
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
    suspend fun consume(payload: String) {
        // The catch-all this used to carry ("poison-pill safety … same convention as
        // KycAmlEventConsumer") acked a lost projection: a revocation that failed to apply leaves
        // the party marked as consenting, i.e. still receiving marketing they withdrew consent for.
        // Only an UNPARSEABLE payload is a poison pill (#5698); a party-db failure is retried and
        // rethrown below.
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: JacksonException) {
            log.errorf(e, "Unparseable marketing consent event, acking: %.300s", payload)
            return
        }
        run {
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

            EventRetry.withRetry(log, "marketing consent projection", partyId) {
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
                    else -> Unit // ConsentRejected or any future event type: nothing to project.
                }
            }
        }
    }

    private fun JsonNode.asUuidOrNull(): UUID? = runCatching { UUID.fromString(asText()) }.getOrNull()

    private fun JsonNode.asInstantOrNull(): Instant? = runCatching { Instant.parse(asText()) }.getOrNull()
}
