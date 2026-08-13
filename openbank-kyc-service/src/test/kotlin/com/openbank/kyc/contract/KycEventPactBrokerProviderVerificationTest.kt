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
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycEvents
import com.openbank.kyc.domain.model.RiskLevel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Broker-side provider verification for openbank-kyc-service, the published-result counterpart to
 * [KycEventPactProviderVerificationTest].
 *
 * WHY BOTH EXIST. A `@PactFolder` test replays the COMMITTED pact from disk: it proves this
 * provider still honours the contract, on every PR, with no infrastructure. It never contacts
 * the broker, so it publishes nothing — and `can-i-deploy` reads published verification
 * results, not green test runs. Without this class the broker never learned that
 * openbank-kyc-service verifies anything, so its consumers (openbank-onboarding-service) stayed
 * permanently UNVERIFIED and could not be deployed (issue #3232).
 *
 * A second `@Provider("openbank-kyc-service")` class is safe here for the reason
 * CLAUDE.md gives for ledger-service's identical pair: the collision it warns about is HTTP vs
 * MESSAGE target dispatch fighting over the same `@BeforeEach`, and both classes here use the
 * same target type, so verifying the same interactions from two pact sources is at worst
 * redundant, never colliding.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, which have no broker
 * configured. It runs on the main push, where `_service-ci.yml` sets `PUBLISH_RESULTS=true`
 * — that is the run whose result `can-i-deploy` gates the deploy on. The `@PactFolder` class
 * keeps running unconditionally, so PR-time contract coverage is unchanged by this addition.
 */
@Provider("openbank-kyc-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class KycEventPactBrokerProviderVerificationTest {

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
