// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.contract

import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycEvents
import com.openbank.kyc.domain.model.RiskLevel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Provider-side MESSAGE contract verification for kyc-case events (ADR-0063, issue #468 —
 * onboarding -> party/kyc/sca edge). First-ever pact PROVIDER role for kyc-service — it was only
 * ever a message CONSUMER before (see the kyc->party edge, `PartyEventMessagePactConsumerTest`
 * in kyc-service). Plain JUnit + [MessageTestTarget] — no Quarkus boot needed, mirroring
 * `PartyEventPactProviderVerificationTest`'s original (pre-HTTP) shape. Each message mirrors the
 * wire shape built by [com.openbank.kyc.domain.model.KycEvents] and is serialized with
 * the same Jackson modules so the contract is verified against the real envelope.
 *
 * Reads the consumer pact from the git-pact folder (`@PactFolder`, resolved relative to this
 * module's working directory at `../pacts` = the monorepo-root `pacts/` dir) and replays each
 * interaction. This always runs — no broker, no gate, no CI secret required.
 */
@Provider("openbank-kyc-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class KycEventPactProviderVerificationTest {

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setTarget(context: PactVerificationContext?) {
        context?.let { it.target = MessageTestTarget(listOf("com.openbank.kyc.contract")) }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    // Built by the PRODUCTION envelope builder (KycEvents), not by a hand-rolled copy of it: a
    // second copy of the envelope moves with the first and keeps passing against a shape the
    // service no longer emits. Issue #4007 moved these events onto `kyc_outbox`; the builder is
    // now the single place the wire shape is defined, and this replays exactly it.
    private fun event(eventType: String, status: KycCaseStatus): String {
        val now = Instant.now()
        val case = KycCase(
            id = UUID.randomUUID(),
            partyId = UUID.randomUUID(),
            status = status,
            riskLevel = RiskLevel.MEDIUM,
            assignedTo = null,
            checks = emptyList(),
            notes = null,
            reviewedBy = null,
            reviewedAt = null,
            expiresAt = null,
            createdAt = now,
            updatedAt = now,
        )
        val built = when (eventType) {
            "KYC_CASE_OPENED" -> KycEvents.caseOpened(case, now)
            "KYC_CASE_STATUS_CHANGED" -> KycEvents.caseStatusChanged(case, now)
            "KYC_CASE_APPROVED" -> KycEvents.caseApproved(case, now)
            "KYC_CASE_REJECTED" -> KycEvents.caseRejected(case, now)
            else -> error("no KycEvents builder for $eventType")
        }
        return objectMapper.writeValueAsString(built.envelope)
    }

    @State("a KYC case has been opened")
    fun kycCaseHasBeenOpened() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a KYC_CASE_OPENED event")
    fun produceKycCaseOpenedEvent(): String = event("KYC_CASE_OPENED", KycCaseStatus.OPEN)

    @State("a KYC case status has changed")
    fun kycCaseStatusHasChanged() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a KYC_CASE_STATUS_CHANGED event")
    fun produceKycCaseStatusChangedEvent(): String = event("KYC_CASE_STATUS_CHANGED", KycCaseStatus.UNDER_REVIEW)

    @State("a KYC case has been approved")
    fun kycCaseHasBeenApproved() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a KYC_CASE_APPROVED event")
    fun produceKycCaseApprovedEvent(): String = event("KYC_CASE_APPROVED", KycCaseStatus.APPROVED)

    @State("a KYC case has been rejected")
    fun kycCaseHasBeenRejected() {
        // No setup: produced deterministically by the @PactVerifyProvider method below.
    }

    @PactVerifyProvider("a KYC_CASE_REJECTED event")
    fun produceKycCaseRejectedEvent(): String = event("KYC_CASE_REJECTED", KycCaseStatus.REJECTED)
}
