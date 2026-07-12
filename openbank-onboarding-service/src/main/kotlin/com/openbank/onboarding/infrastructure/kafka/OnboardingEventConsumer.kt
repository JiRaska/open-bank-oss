// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.onboarding.application.usecase.OnboardingProjectionService
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingEvent
import com.openbank.onboarding.domain.model.PartyStage
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Consumes events from party-, kyc- and sca-service topics and feeds the onboarding read-model.
 *
 * Uses `suspend @Incoming` — same pattern as balance-service's LedgerProjectionConsumer.
 * Quarkus dispatches suspend @Incoming handlers on the Vert.x event loop with a proper
 * duplicated context, so awaitSuspending() inside the projection service works correctly.
 *
 * Poison-pill protection: any parse or projection failure is caught, logged, and the message
 * is acked (return). This is correct for a read-model projection: a single bad event must not
 * wedge the consumer group; the canonical source of truth (party/kyc/sca) can be replayed.
 */
@ApplicationScoped
class OnboardingEventConsumer(private val clock: Clock) {

    @Inject
    lateinit var projection: OnboardingProjectionService

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(OnboardingEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consumePartyEvent(payload: String) {
        // Fast-path: intercept PARTY_ERASED before routing through parsePartyEvent, which
        // returns null for unknown event types and would silently drop the erasure request.
        // This mirrors the pattern used in kyc-service's PartyEventConsumer (GDPR Art. 17).
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to parse JSON payload: %.200s", payload)
            return
        }

        if (node.path("eventType").asText() == "PARTY_ERASED") {
            val partyId = node.path("partyId").asUuid()
            if (partyId == null) {
                log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
                return
            }
            try {
                projection.eraseParty(partyId)
                log.infof("[party-events-in] GDPR Art. 17: erased onboarding read-model for party %s", partyId)
            } catch (e: Exception) {
                log.errorf(
                    e,
                    "[party-events-in] GDPR Art. 17: failed to erase onboarding read-model for party %s",
                    partyId,
                )
            }
            return
        }

        val event = try {
            parsePartyEvent(node)
        } catch (e: Exception) {
            log.errorf(e, "[party-events-in] Failed to map event: %.200s", payload)
            return
        } ?: return
        project(event, "party-events-in")
    }

    @Incoming("kyc-events-in")
    suspend fun consumeKycEvent(payload: String) {
        val event = parseEvent(payload, "kyc-events-in") { parseKycEvent(it) } ?: return
        project(event, "kyc-events-in")
    }

    @Incoming("sca-events-in")
    suspend fun consumeScaEvent(payload: String) {
        val event = parseEvent(payload, "sca-events-in") { parseScaEvent(it) } ?: return
        project(event, "sca-events-in")
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parsePartyEvent(node: JsonNode): OnboardingEvent? {
        val type = node.path("eventType").asText()
        val partyId = node.path("partyId").asUuid() ?: return null
        val occurredAt = node.path("occurredAt").asInstant() ?: clock.instant()
        return when (type) {
            "PARTY_CREATED" -> OnboardingEvent.PartyCreated(
                partyId = partyId,
                legalName = node.path("legalName").asText(""),
                email = node.path("email").asText(""),
                occurredAt = occurredAt,
            )
            // KafkaPartyEventPublisher actually publishes "KYC_STATUS_CHANGED" (see
            // publishKycStatusChanged) — "KYC_STATUS_UPDATED" was never a real event type, so
            // every KYC/AML status transition from party-service was silently dropped here.
            "PARTY_STATUS_CHANGED", "KYC_STATUS_UPDATED", "KYC_STATUS_CHANGED" -> {
                val rawStatus = node.path("newStatus").asText().takeIf { it.isNotBlank() }
                    ?: node.path("status").asText()
                val stage = runCatching { PartyStage.valueOf(rawStatus) }.getOrNull() ?: return null
                OnboardingEvent.PartyStatusChanged(partyId, stage, occurredAt)
            }
            else -> null
        }
    }

    private fun parseKycEvent(node: JsonNode): OnboardingEvent? {
        val type = node.path("eventType").asText()
        val partyId = node.path("partyId").asUuid() ?: return null
        val occurredAt = node.path("occurredAt").asInstant() ?: clock.instant()
        return when (type) {
            "KYC_CASE_OPENED" -> {
                // kyc-service emits the case id as "kycCaseId"; accept "caseId" too for resilience.
                val caseId = (node.path("kycCaseId").asUuid() ?: node.path("caseId").asUuid()) ?: return null
                OnboardingEvent.KycCaseOpened(partyId, caseId, occurredAt)
            }
            "KYC_CASE_STATUS_CHANGED", "KYC_CASE_APPROVED", "KYC_CASE_REJECTED" -> {
                // kyc-service emits the case id as "kycCaseId"; accept "caseId" too for resilience.
                val caseId = (node.path("kycCaseId").asUuid() ?: node.path("caseId").asUuid()) ?: return null
                // KycEventPublisher.publish always serializes the field as "status", never
                // "newStatus" — KYC_CASE_STATUS_CHANGED has no other fallback, so every
                // non-terminal case-status transition (e.g. UNDER_REVIEW, PEP escalation) was
                // silently dropped here (only the terminal APPROVED/REJECTED hardcoded defaults
                // ever worked, and only by coincidence — they never actually read the payload).
                val rawStatus = node.path("newStatus").asText().takeIf { it.isNotBlank() }
                    ?: node.path("status").asText().takeIf { it.isNotBlank() }
                    ?: when (type) {
                        "KYC_CASE_APPROVED" -> "APPROVED"
                        "KYC_CASE_REJECTED" -> "REJECTED"
                        else -> return null
                    }
                val stage = runCatching { KycStage.valueOf(rawStatus) }.getOrNull() ?: return null
                OnboardingEvent.KycStatusChanged(partyId, caseId, stage, occurredAt)
            }
            else -> null
        }
    }

    private fun parseScaEvent(node: JsonNode): OnboardingEvent? {
        val type = node.path("eventType").asText()
        val partyId = node.path("partyId").asUuid() ?: return null
        val occurredAt = node.path("occurredAt").asInstant() ?: clock.instant()
        return when (type) {
            "DEVICE_ENROLLED" -> OnboardingEvent.DeviceEnrolled(
                partyId = partyId,
                credentialId = node.path("credentialId").asText(""),
                occurredAt = occurredAt,
            )
            else -> null
        }
    }

    // ── Dispatch helpers ──────────────────────────────────────────────────────

    private fun parseEvent(payload: String, topic: String, parse: (JsonNode) -> OnboardingEvent?): OnboardingEvent? {
        val node = try {
            objectMapper.readTree(payload)
        } catch (e: Exception) {
            log.errorf(e, "[%s] Failed to parse JSON payload: %.200s", topic, payload)
            return null
        }
        return try {
            parse(node)
        } catch (e: Exception) {
            log.errorf(e, "[%s] Failed to map event: %.200s", topic, payload)
            null
        }
    }

    private suspend fun project(event: OnboardingEvent, topic: String) {
        try {
            projection.applyEvent(event)
        } catch (e: Exception) {
            log.errorf(e, "[%s] Projection failed for event type %s", topic, event::class.simpleName)
            // Ack the message (don't rethrow) — the read-model can be re-seeded from events if needed.
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private fun JsonNode.asUuid(): UUID? = runCatching { UUID.fromString(asText()) }.getOrNull()
    private fun JsonNode.asInstant(): Instant? = runCatching { Instant.parse(asText()) }.getOrNull()
}
