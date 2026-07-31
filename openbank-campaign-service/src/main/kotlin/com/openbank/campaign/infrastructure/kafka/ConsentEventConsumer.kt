// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.domain.model.EnrolmentState
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * ADR-0200 D2 push mechanism: consumes `openbank.consent.events` and signals every live journey of
 * the affected party on `ConsentRevoked` for a marketing scope, so a journey terminates mid-flight
 * rather than at its next step. The per-send pull check (in the delivery activity) is the other
 * half — both are required, per D2.
 */
@ApplicationScoped
class ConsentEventConsumer(
    private val enrolments: EnrolmentRepository,
    private val journeys: JourneySignaller,
    private val mapper: ObjectMapper,
) {

    private val log = Logger.getLogger(ConsentEventConsumer::class.java)

    /**
     * `suspend`, NOT a plain method wrapping `runBlocking`.
     *
     * SmallRye invokes a plain `@Incoming` method on a Kafka consumer thread, which carries no
     * Vert.x context, so the first reactive Panache call threw
     * `InternalStateAssertions.assertUseOnEventLoop` and the message was nacked fail-stop. The
     * channel then stopped consuming entirely — the readiness check went DOWN while the pod stayed
     * up, and a revocation could never terminate a journey (ADR-0200 D2). Measured during the
     * #2749 rollout, after three other defects on this same path had already been fixed.
     *
     * Same footgun the fleet documents for `@Scheduled` (#2190/#2191/#2194) — a Kafka consumer is
     * simply another place with no Vert.x context, and `runBlocking` hides that just as well here.
     */
    @Incoming("consent-events-in")
    suspend fun onConsentEvent(payload: String) {
        val event = mapper.readTree(payload)
        if (event.eventType() != "ConsentRevoked" && event.eventType() != "ConsentExpired") return
        val scopes = event.path("scopes").map { it.asText() }
        if (scopes.none { it.startsWith("MARKETING_COMMS") }) return
        val partyId = runCatching { UUID.fromString(event.path("partyId").asText()) }.getOrNull() ?: return

        // First slice: signal journeys across all ACTIVE campaigns for this party. Campaigns
        // query enrolments by campaign, so we enumerate via the party's enrolments.
        enrolments.listByParty(partyId).forEach { enrolment ->
            if (enrolment.state == EnrolmentState.ACTIVE) {
                journeys.signalConsentRevoked(enrolment.campaignId, partyId)
            }
        }
        log.infof("Consent revoked for party %s — signalled live journeys", partyId)
    }

    private fun JsonNode.eventType(): String = path("type").asText().ifBlank { path("eventType").asText() }
}
