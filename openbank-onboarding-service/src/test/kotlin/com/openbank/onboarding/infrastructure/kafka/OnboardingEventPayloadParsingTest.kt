// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.onboarding.application.usecase.OnboardingProjectionService
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingEvent
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.domain.model.ProjectionResult
import com.openbank.onboarding.infrastructure.observability.ProjectionOutcomeMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Wire-format edge cases of the three payload parsers.
 *
 * Every defect this file pins is the same shape: the producer sends a field spelling the parser
 * does not read, so the event is dropped on the quiet path with no lag, no error and no log. That
 * shape cost the platform 15 DEVICE_ENROLLED enrolments and every non-terminal KYC transition
 * (#4353, #6248), and it is invisible to any test that builds the JSON from the parser's own
 * expectations — so these assert the ALTERNATIVE spellings the producers actually emit.
 */
class OnboardingEventPayloadParsingTest {

    private val projection = mockk<OnboardingProjectionService>()
    private val metrics = mockk<ProjectionOutcomeMetrics>(relaxed = true)
    private val fallbackNow = Instant.parse("2026-04-01T09:00:00Z")
    private lateinit var consumer: OnboardingEventConsumer

    private val partyId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val caseId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        consumer = OnboardingEventConsumer(Clock.fixed(fallbackNow, ZoneOffset.UTC)).also {
            it.projection = projection
            it.objectMapper = ObjectMapper()
            it.metrics = metrics
        }
    }

    private fun captureProjected(consume: suspend () -> Unit): OnboardingEvent {
        val captured = slot<OnboardingEvent>()
        coEvery { projection.applyEvent(capture(captured)) } returns ProjectionResult.APPLIED
        runBlocking { consume() }
        return captured.captured
    }

    private fun assertDropped(consume: suspend () -> Unit, topic: String) {
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED
        runBlocking { consume() }
        coVerify(exactly = 0) { projection.applyEvent(any()) }
        verify { metrics.record(topic, ProjectionOutcomeMetrics.Outcome.UNRECOGNISED) }
    }

    // ── kyc-events-in ────────────────────────────────────────────────────────

    @Test
    fun `KYC_CASE_OPENED reads the kycCaseId spelling kyc-service actually emits`() {
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_OPENED","partyId":"$partyId","kycCaseId":"$caseId",
                   "occurredAt":"2026-03-05T10:00:00Z"}""",
            )
        }

        assertThat(event).isInstanceOf(OnboardingEvent.KycCaseOpened::class.java)
        val opened = event as OnboardingEvent.KycCaseOpened
        assertThat(opened.kycCaseId).isEqualTo(caseId)
        assertThat(opened.partyId).isEqualTo(partyId)
        assertThat(opened.occurredAt).isEqualTo(Instant.parse("2026-03-05T10:00:00Z"))
    }

    @Test
    fun `KYC_CASE_OPENED falls back to the caseId spelling`() {
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_OPENED","partyId":"$partyId","caseId":"$caseId"}""",
            )
        }

        assertThat((event as OnboardingEvent.KycCaseOpened).kycCaseId).isEqualTo(caseId)
    }

    @Test
    fun `KYC_CASE_OPENED with no case id at all is dropped rather than projected with a fake id`() {
        assertDropped(
            { consumer.consumeKycEvent("""{"eventType":"KYC_CASE_OPENED","partyId":"$partyId"}""") },
            "kyc-events-in",
        )
    }

    @Test
    fun `KYC_CASE_APPROVED without a status field still projects the terminal stage`() {
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_APPROVED","partyId":"$partyId","kycCaseId":"$caseId"}""",
            )
        }

        assertThat((event as OnboardingEvent.KycStatusChanged).newStatus).isEqualTo(KycStage.APPROVED)
    }

    @Test
    fun `KYC_CASE_REJECTED without a status field still projects the terminal stage`() {
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_REJECTED","partyId":"$partyId","kycCaseId":"$caseId"}""",
            )
        }

        assertThat((event as OnboardingEvent.KycStatusChanged).newStatus).isEqualTo(KycStage.REJECTED)
    }

    @Test
    fun `an explicit status beats the eventType-derived default`() {
        // A KYC_CASE_APPROVED carrying an explicit status must not be silently rewritten to
        // APPROVED — the hardcoded default is a fallback, not an override.
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_APPROVED","partyId":"$partyId","kycCaseId":"$caseId",
                   "status":"UNDER_REVIEW"}""",
            )
        }

        assertThat((event as OnboardingEvent.KycStatusChanged).newStatus).isEqualTo(KycStage.UNDER_REVIEW)
    }

    @Test
    fun `a blank status falls through to the next spelling instead of failing valueOf`() {
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_STATUS_CHANGED","partyId":"$partyId","kycCaseId":"$caseId",
                   "newStatus":"","status":"DOCUMENTS_REQUIRED"}""",
            )
        }

        assertThat((event as OnboardingEvent.KycStatusChanged).newStatus)
            .isEqualTo(KycStage.DOCUMENTS_REQUIRED)
    }

    @Test
    fun `a KYC_CASE_STATUS_CHANGED with no status at all is dropped, not defaulted`() {
        assertDropped(
            {
                consumer.consumeKycEvent(
                    """{"eventType":"KYC_CASE_STATUS_CHANGED","partyId":"$partyId","kycCaseId":"$caseId"}""",
                )
            },
            "kyc-events-in",
        )
    }

    @Test
    fun `a status value KycStage does not know is dropped rather than crashing the consumer`() {
        assertDropped(
            {
                consumer.consumeKycEvent(
                    """{"eventType":"KYC_CASE_STATUS_CHANGED","partyId":"$partyId","kycCaseId":"$caseId",
                       "status":"SOMETHING_NEW"}""",
                )
            },
            "kyc-events-in",
        )
    }

    @Test
    fun `an unparseable occurredAt falls back to the clock rather than dropping the event`() {
        val event = captureProjected {
            consumer.consumeKycEvent(
                """{"eventType":"KYC_CASE_OPENED","partyId":"$partyId","kycCaseId":"$caseId",
                   "occurredAt":"not-a-timestamp"}""",
            )
        }

        assertThat(event.occurredAt).isEqualTo(fallbackNow)
    }

    @Test
    fun `a payload with no partyId is dropped on every kyc event type`() {
        assertDropped(
            { consumer.consumeKycEvent("""{"eventType":"KYC_CASE_OPENED","kycCaseId":"$caseId"}""") },
            "kyc-events-in",
        )
    }

    // ── sca-events-in ────────────────────────────────────────────────────────

    @Test
    fun `DEVICE_ENROLLED prefers credentialId over deviceId`() {
        val event = captureProjected {
            consumer.consumeScaEvent(
                """{"eventType":"DEVICE_ENROLLED","partyId":"$partyId","credentialId":"cred-1",
                   "deviceId":"dev-1"}""",
            )
        }

        assertThat((event as OnboardingEvent.DeviceEnrolled).credentialId).isEqualTo("cred-1")
    }

    @Test
    fun `DEVICE_ENROLLED falls back to deviceId when credentialId is blank`() {
        // An empty credential id collapses every one of a party's devices onto the same ledger
        // key and silently caps device_count at 1 — the fallback is what stops that.
        val event = captureProjected {
            consumer.consumeScaEvent(
                """{"eventType":"DEVICE_ENROLLED","partyId":"$partyId","credentialId":"","deviceId":"dev-9"}""",
            )
        }

        assertThat((event as OnboardingEvent.DeviceEnrolled).credentialId).isEqualTo("dev-9")
    }

    @Test
    fun `an sca event type onboarding does not project is dropped quietly and counted`() {
        assertDropped(
            { consumer.consumeScaEvent("""{"eventType":"DEVICE_REVOKED","partyId":"$partyId"}""") },
            "sca-events-in",
        )
    }

    // ── party-events-in ──────────────────────────────────────────────────────

    @Test
    fun `PARTY_CREATED carries the legal name and email onto the projection`() {
        val event = captureProjected {
            consumer.consumePartyEvent(
                """{"eventType":"PARTY_CREATED","partyId":"$partyId","legalName":"Jan Novak",
                   "email":"jan@example.test"}""",
            )
        }

        val created = event as OnboardingEvent.PartyCreated
        assertThat(created.legalName).isEqualTo("Jan Novak")
        assertThat(created.email).isEqualTo("jan@example.test")
    }

    @Test
    fun `PARTY_CREATED with no name or email projects blanks rather than dropping the party`() {
        val event = captureProjected {
            consumer.consumePartyEvent("""{"eventType":"PARTY_CREATED","partyId":"$partyId"}""")
        }

        val created = event as OnboardingEvent.PartyCreated
        assertThat(created.legalName).isEmpty()
        assertThat(created.email).isEmpty()
    }

    @Test
    fun `PARTY_STATUS_CHANGED reads newStatus first and the status fallback second`() {
        val fromNewStatus = captureProjected {
            consumer.consumePartyEvent(
                """{"eventType":"PARTY_STATUS_CHANGED","partyId":"$partyId","newStatus":"ACTIVE",
                   "status":"CLOSED"}""",
            )
        }
        assertThat((fromNewStatus as OnboardingEvent.PartyStatusChanged).newStatus).isEqualTo(PartyStage.ACTIVE)

        val fromStatus = captureProjected {
            consumer.consumePartyEvent(
                """{"eventType":"PARTY_STATUS_CHANGED","partyId":"$partyId","status":"SUSPENDED"}""",
            )
        }
        assertThat((fromStatus as OnboardingEvent.PartyStatusChanged).newStatus).isEqualTo(PartyStage.SUSPENDED)
    }

    @Test
    fun `a party status PartyStage does not know is dropped rather than mis-projected`() {
        assertDropped(
            {
                consumer.consumePartyEvent(
                    """{"eventType":"PARTY_STATUS_CHANGED","partyId":"$partyId","newStatus":"MERGED"}""",
                )
            },
            "party-events-in",
        )
    }

    @Test
    fun `a party event with an unknown type is counted as UNRECOGNISED, not FAILED`() {
        assertDropped(
            { consumer.consumePartyEvent("""{"eventType":"PARTY_MERGED","partyId":"$partyId"}""") },
            "party-events-in",
        )
        verify(exactly = 0) { metrics.record("party-events-in", ProjectionOutcomeMetrics.Outcome.FAILED) }
    }

    @Test
    fun `a successful projection is counted as PROJECTED on the topic it arrived on`(): Unit = runBlocking {
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED

        consumer.consumeScaEvent(
            """{"eventType":"DEVICE_ENROLLED","partyId":"$partyId","credentialId":"cred-1"}""",
        )

        verify(exactly = 1) { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED) }
    }
}
