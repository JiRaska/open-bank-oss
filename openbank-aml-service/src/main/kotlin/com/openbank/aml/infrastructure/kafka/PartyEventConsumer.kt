// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.messaging.EventRetry
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Opens an onboarding AML screening case when a party is created (ADR-0267 §2 — the AML
 * outcome is the second key of the party activation gate), and — in the sandbox — auto-clears
 * it so the party clears the AML key of that gate without an analyst. Emits
 * aml.case.status_changed.v1 (CLEARED) on openbank.aml.events, which party-service consumes
 * for its KYC+AML two-key gate.
 *
 * Idempotent: the case idempotency key is "<partyId>:CUSTOMER_ONBOARDING", so a redelivered
 * PARTY_CREATED reuses the existing case; the auto-clear is skipped once the case is terminal.
 *
 * PARTY_ERASED: anonymises PII in all AML cases for the party (GDPR Art. 17 right of erasure).
 * The case row itself is retained for audit/SAR obligations; only the personal data fields are
 * nulled and customerReference is replaced with "ERASED-<partyId>".
 *
 * Failure handling (#5698): an UNPARSEABLE payload is logged and acked — a genuine poison pill,
 * since replaying it fails identically forever. A failure of aml-db is the opposite case: the event
 * is fine, the screening must still happen, so it is retried and then RETHROWN for the connector to
 * dead-letter. Acking it would leave an onboarding party with no AML screening at all and nothing
 * anywhere saying so.
 *
 * Auto-clear is sandbox-only (openbank.aml.auto-clear, default false). Production keeps the
 * four-eyes decision endpoint as the only path to CLEARED/BLOCKED. No ADR decides this flag:
 * ADR-0116 §4 decides only the KYC equivalent (openbank.kyc.auto-approve); the AML side is
 * recorded in this service's docs/ only (#5785).
 */
@ApplicationScoped
class PartyEventConsumer(
    private val amlUseCase: AmlCaseUseCase,
    private val amlCaseRepository: AmlCaseRepository,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.aml.auto-clear", defaultValue = "false")
    private val autoClear: Boolean,
) {
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
            "PARTY_CREATED" -> handleCreated(partyId, node, payload)
            "PARTY_ERASED" -> handleErased(partyId, payload)
        }
    }

    private suspend fun handleCreated(partyId: UUID?, node: com.fasterxml.jackson.databind.JsonNode, payload: String) {
        if (node.path("partyType").asText("") != "INDIVIDUAL") return
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_CREATED without a valid partyId, skipping: %.200s", payload)
            return
        }
        EventRetry.withRetry(log, "PARTY_CREATED AML screening", partyId) {
            val case = amlUseCase.createCase(
                CreateAmlCaseCommand(
                    idempotencyKey = "$partyId:CUSTOMER_ONBOARDING",
                    partyId = partyId,
                    accountId = null,
                    transactionId = null,
                    customerReference = "onboarding-$partyId",
                    screeningType = ScreeningType.CUSTOMER_ONBOARDING,
                    riskLevel = AmlRiskLevel.LOW,
                    alertCode = "ONBOARDING_SCREENING",
                    alertDetail = null,
                    matchedEntity = null,
                ),
            )
            log.infof("[party-events-in] Opened onboarding AML case %s for party %s", case.id, partyId)

            if (autoClear && case.status != AmlCaseStatus.CLEARED && case.status != AmlCaseStatus.BLOCKED) {
                amlUseCase.updateDecision(
                    UpdateAmlDecisionCommand(
                        caseId = case.id,
                        targetStatus = AmlCaseStatus.CLEARED,
                        decisionReason = "Sandbox auto-clear (no adverse match)",
                        assignedAnalyst = "SANDBOX_BOT",
                        decidedBy = "SANDBOX_SYSTEM",
                    ),
                )
                log.infof("[party-events-in] Auto-cleared AML case %s for party %s", case.id, partyId)
            }
        }
    }

    private suspend fun handleErased(partyId: UUID?, payload: String) {
        if (partyId == null) {
            log.warnf("[party-events-in] PARTY_ERASED without valid partyId, skipping: %.200s", payload)
            return
        }
        // An acked-but-failed erasure is worse than a stalled workflow: the PII stays, and the log
        // line says it went. anonymizeByPartyId is idempotent, so retry and redelivery are safe.
        EventRetry.withRetry(log, "PARTY_ERASED AML anonymisation", partyId) {
            val count = amlCaseRepository.anonymizeByPartyId(partyId)
            log.infof(
                "[party-events-in] GDPR Art. 17: anonymised PII in %d AML case(s) for erased party %s",
                count,
                partyId,
            )
        }
    }
}
