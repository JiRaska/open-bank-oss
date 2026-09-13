// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyb.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Listens for the entity party reaching ACTIVE — the KYC + AML two-key gate of ADR-0267 — and
 * completes the business case (ADR-0284 D5). `KYC_STATUS_CHANGED` and `PARTY_UPDATED` both carry
 * `status`; whichever arrives first with `ACTIVE` wins, and the use case is idempotent.
 *
 * A parse failure is acked (replaying it fails identically forever); a use-case failure is
 * retried a bounded number of times and then rethrown, and the channel's configured
 * `failure-strategy` decides what follows (application.yaml: dead-letter-queue).
 */
@ApplicationScoped
class PartyEventConsumer {

    @Inject lateinit var onboarding: BusinessOnboardingUseCase

    @Inject lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(PartyEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.errorf(e, "[party-events-in] unparseable payload: %.200s", payload)
            return
        }
        val eventType = node.path("eventType").asText("")
        if (eventType != "KYC_STATUS_CHANGED" && eventType != "PARTY_UPDATED") return
        if (node.path("status").asText("") != "ACTIVE") return
        val partyType = node.path("partyType").asText("")
        if (partyType != "COMPANY" && partyType != "SOLE_TRADER") return
        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull() ?: return
        EventRetry.withRetry(log, "[party-events-in] entity party ACTIVE", partyId) {
            onboarding.entityPartyActivated(partyId)
        }
    }
}
