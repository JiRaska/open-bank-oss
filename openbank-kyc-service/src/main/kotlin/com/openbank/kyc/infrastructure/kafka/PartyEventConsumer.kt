// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.kyc.application.KycService
import com.openbank.kyc.application.port.out.KycCaseRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Consumes party-service events and auto-opens a KYC case when a new party is created, so the
 * onboarding funnel no longer depends on an operator opening the case by hand (ADR-0068).
 *
 * Uses `suspend @Incoming` — the same pattern as onboarding-service's OnboardingEventConsumer:
 * Quarkus dispatches suspend handlers on the Vert.x event loop with a duplicated context, so the
 * downstream reactive persistence inside [KycService] runs correctly.
 *
 * Poison-pill protection: any parse or domain failure is caught, logged and the message is acked
 * (the method returns normally). A single malformed event must not wedge the consumer group; the
 * canonical party stream can be replayed, and [KycService.openCaseForParty] is idempotent.
 */
@ApplicationScoped
class PartyEventConsumer {

    @Inject
    lateinit var kycService: KycService

    @Inject
    lateinit var kycCaseRepository: KycCaseRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(PartyEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON payload: %.200s", payload)
            return
        }

        val eventType = node.path("eventType").asText()
        val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull()

        when (eventType) {
            "PARTY_CREATED" -> handleCreated(partyId, payload)
            "PARTY_ERASED" -> handleErased(partyId, payload)
        }
    }

    private suspend fun handleCreated(partyId: UUID?, payload: String) {
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_CREATED without a valid partyId, skipping: %.200s", payload)
            return
        }
        try {
            val (case, created) = kycService.openCaseForParty(partyId)
            if (created) {
                log.infof("[party-events-in] Auto-opened KYC case %s for party %s", case.id, partyId)
            } else {
                log.infof(
                    "[party-events-in] KYC case %s already open for party %s (idempotent reuse)",
                    case.id,
                    partyId,
                )
            }
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to auto-open KYC case for party %s", partyId)
        }
    }

    private suspend fun handleErased(partyId: UUID?, payload: String) {
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
            return
        }
        try {
            kycCaseRepository.anonymizeByPartyId(partyId)
            log.infof("[party-events-in] GDPR Art. 17: anonymised KYC PII for erased party %s", partyId)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to anonymise KYC PII for party %s", partyId)
        }
    }
}
