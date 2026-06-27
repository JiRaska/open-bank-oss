// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.aml.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.`in`.AmlCaseUseCase
import com.openbank.aml.application.port.`in`.CreateAmlCaseCommand
import com.openbank.aml.application.port.`in`.UpdateAmlDecisionCommand
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.AmlRiskLevel
import com.openbank.aml.domain.model.ScreeningType
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Opens an onboarding AML screening case when a party is created (ADR-0073), and — in the
 * sandbox — auto-clears it so the party clears the AML key of the activation gate without an
 * analyst. Emits aml.case.status_changed.v1 (CLEARED) on openbank.aml.events, which
 * party-service consumes for its KYC+AML two-key gate.
 *
 * Idempotent: the case idempotency key is "<partyId>:CUSTOMER_ONBOARDING", so a redelivered
 * PARTY_CREATED reuses the existing case; the auto-clear is skipped once the case is terminal.
 * Poison-pill safe: failures are logged and acked.
 *
 * Auto-clear is sandbox-only (openbank.aml.auto-clear, default false). Production keeps the
 * four-eyes decision endpoint as the only path to CLEARED/BLOCKED.
 */
@ApplicationScoped
class PartyEventConsumer(
    private val amlUseCase: AmlCaseUseCase,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(name = "openbank.aml.auto-clear", defaultValue = "false")
    private val autoClear: Boolean,
) {
    private val log = Logger.getLogger(PartyEventConsumer::class.java)

    @Incoming("party-events-in")
    suspend fun consume(payload: String) {
        try {
            val node = objectMapper.readTree(payload)
            if (node.path("eventType").asText() != "PARTY_CREATED") return
            if (node.path("partyType").asText("") != "INDIVIDUAL") return
            val partyId = runCatching { UUID.fromString(node.path("partyId").asText()) }.getOrNull() ?: return

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
            log.infof("Opened onboarding AML case %s for party %s", case.id, partyId)

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
                log.infof("Auto-cleared AML case %s for party %s", case.id, partyId)
            }
        } catch (e: Exception) {
            log.errorf(e, "Failed to handle party event: %.300s", payload)
        }
    }
}
