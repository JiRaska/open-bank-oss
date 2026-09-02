// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.onboarding.application.usecase.OnboardingProjectionService
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingEvent
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.domain.model.ProjectionResult
import com.openbank.onboarding.infrastructure.observability.ProjectionOutcomeMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [OnboardingEventConsumer] focusing on the PARTY_ERASED / GDPR Art. 17 path.
 *
 * The consumer is constructed directly with mocked dependencies — no Quarkus CDI context needed.
 */
class OnboardingEventConsumerTest {

    private val projection = mockk<OnboardingProjectionService>()
    private val objectMapper = ObjectMapper()
    private val metrics = mockk<ProjectionOutcomeMetrics>(relaxed = true)
    private lateinit var consumer: OnboardingEventConsumer

    @BeforeEach
    fun setUp() {
        consumer = OnboardingEventConsumer(Clock.systemUTC()).also {
            it.projection = projection
            it.objectMapper = objectMapper
            it.metrics = metrics
        }
    }

    // ── DEVICE_ENROLLED (#4353) ───────────────────────────────────────────────

    /**
     * The payload is copied verbatim from a real `openbank.sca.events` message on the sandbox
     * (2026-08-13), with the `eventType` this PR adds to the producer. Writing the JSON by hand
     * from the parser's expectations is what let this diverge for the whole life of the topic:
     * every unit test on both sides was green while the two artifacts disagreed on the wire.
     */
    @Test
    fun `consumeScaEvent projects DEVICE_ENROLLED from the real sca-service payload`(): Unit = runBlocking {
        val partyId = UUID.fromString("d03712b8-8814-40a7-a683-ac0c6a307204")
        val payload = """{"eventType":"DEVICE_ENROLLED",""" +
            """"deviceId":"78823928-36a6-44c3-bf4d-2ceb657cd623",""" +
            """"partyId":"$partyId",""" +
            """"credentialId":"e2e-1c0a4767-aed7-4e9a-9f91-ea8ba3e0493e",""" +
            """"algorithm":"ES256","occurredAt":"2026-08-08T03:20:23.835301445Z"}"""
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED

        consumer.consumeScaEvent(payload)

        coVerify(exactly = 1) {
            projection.applyEvent(
                match { it is OnboardingEvent.DeviceEnrolled && it.partyId == partyId },
            )
        }
        verify(exactly = 1) { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED) }
    }

    /**
     * The defect this outcome exists for (#6248). A DEVICE_ENROLLED whose party has no row yet is
     * consumed successfully and dropped; before the fourth outcome existed it was recorded as
     * PROJECTED, so the alert built for exactly this class of loss compared against a series the
     * drops were inflating.
     *
     * The `exactly = 0` on PROJECTED is the assertion that matters. Without it this test passes
     * unchanged against the old code, which recorded PROJECTED and nothing else — it would be
     * measuring that a counter was touched, not which bucket it landed in.
     */
    @Test
    fun `a seeded projection is recorded as SEEDED_UNKNOWN_PARTY and never as PROJECTED`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED_TO_SEEDED_RECORD

        consumer.consumeScaEvent(
            """{"eventType":"DEVICE_ENROLLED","partyId":"$partyId","credentialId":"c1"}""",
        )

        verify(exactly = 1) {
            metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.SEEDED_UNKNOWN_PARTY)
        }
        verify(exactly = 0) { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED) }
        verify(exactly = 0) { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.FAILED) }
    }

    /**
     * A seed must stay acked. Nacking it would wedge the consumer group on an ordering race
     * that resolves itself, which is the failure mode the original `?: return` was right to
     * avoid — what was wrong was discarding the event, and then reporting that as a success.
     */
    @Test
    fun `a seeded projection does not throw`() {
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED_TO_SEEDED_RECORD

        assertThatCode {
            runBlocking {
                consumer.consumeScaEvent(
                    """{"eventType":"DEVICE_ENROLLED","partyId":"${UUID.randomUUID()}","credentialId":"c1"}""",
                )
            }
        }.doesNotThrowAnyException()
    }

    /**
     * The shape as it actually shipped: no `eventType` in the body. The consumer must not throw
     * and must not project — but it must now SAY so, because this drop was previously
     * indistinguishable from an idle topic.
     */
    @Test
    fun `consumeScaEvent counts a payload with no eventType as UNRECOGNISED`(): Unit = runBlocking {
        val payload = """{"deviceId":"78823928-36a6-44c3-bf4d-2ceb657cd623",""" +
            """"partyId":"d03712b8-8814-40a7-a683-ac0c6a307204",""" +
            """"credentialId":"e2e-1","algorithm":"ES256",""" +
            """"occurredAt":"2026-08-08T03:20:23.835301445Z"}"""

        consumer.consumeScaEvent(payload)

        coVerify(exactly = 0) { projection.applyEvent(any()) }
        verify(exactly = 1) { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED) }
    }

    // ── PARTY_ERASED ──────────────────────────────────────────────────────────

    @Test
    fun `consumePartyEvent calls eraseParty for PARTY_ERASED with valid partyId`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val payload = """{"eventType":"PARTY_ERASED","partyId":"$partyId"}"""
        coEvery { projection.eraseParty(partyId) } just runs

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 1) { projection.eraseParty(partyId) }
    }

    @Test
    fun `consumePartyEvent logs and skips PARTY_ERASED with missing partyId`(): Unit = runBlocking {
        val payload = """{"eventType":"PARTY_ERASED"}"""

        // Should complete without throwing; no eraseParty call expected
        consumer.consumePartyEvent(payload)

        coVerify(exactly = 0) { projection.eraseParty(any()) }
    }

    @Test
    fun `consumePartyEvent logs and skips PARTY_ERASED with blank partyId`(): Unit = runBlocking {
        val payload = """{"eventType":"PARTY_ERASED","partyId":""}"""

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 0) { projection.eraseParty(any()) }
    }

    @Test
    fun `consumePartyEvent does not call eraseParty for PARTY_CREATED events`(): Unit = runBlocking {
        // PARTY_CREATED routes through applyEvent — eraseParty must not be called
        val partyId = UUID.randomUUID()
        val payload = """{"eventType":"PARTY_CREATED","partyId":"$partyId",""" +
            """"legalName":"Alice","email":"a@b.com","occurredAt":"2026-06-01T10:00:00Z"}"""
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 0) { projection.eraseParty(any()) }
    }

    // ── KYC_STATUS_CHANGED (bug: KafkaPartyEventPublisher actually publishes this type, but the
    // parser only matched "PARTY_STATUS_CHANGED" / "KYC_STATUS_UPDATED" — a type string never
    // actually published, so every KYC/AML status transition was silently dropped) ─────────────

    @Test
    fun `consumePartyEvent projects PartyStatusChanged for KYC_STATUS_CHANGED events`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val payload = """{"eventType":"KYC_STATUS_CHANGED","partyId":"$partyId",""" +
            """"status":"ACTIVE","occurredAt":"2026-06-01T10:00:00Z"}"""
        coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED

        consumer.consumePartyEvent(payload)

        coVerify(exactly = 1) {
            projection.applyEvent(
                OnboardingEvent.PartyStatusChanged(
                    partyId,
                    PartyStage.ACTIVE,
                    Instant.parse("2026-06-01T10:00:00Z"),
                ),
            )
        }
    }

    // ── KYC_CASE_STATUS_CHANGED (bug: KycEventPublisher.publish always serializes the field as
    // "status", never "newStatus" — the parser had no fallback to "status", so every non-terminal
    // case-status transition was silently dropped) ──────────────────────────────────────────────

    @Test
    fun `consumeKycEvent reads the 'status' field KycEventPublisher actually sends, not 'newStatus'`(): Unit =
        runBlocking {
            val partyId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val payload = """{"eventType":"KYC_CASE_STATUS_CHANGED","partyId":"$partyId","kycCaseId":"$caseId",""" +
                """"status":"UNDER_REVIEW","occurredAt":"2026-06-01T10:00:00Z"}"""
            coEvery { projection.applyEvent(any()) } returns ProjectionResult.APPLIED

            consumer.consumeKycEvent(payload)

            coVerify(exactly = 1) {
                projection.applyEvent(
                    OnboardingEvent.KycStatusChanged(
                        partyId,
                        caseId,
                        KycStage.UNDER_REVIEW,
                        Instant.parse("2026-06-01T10:00:00Z"),
                    ),
                )
            }
        }

    // ── Transient failure vs poison pill (#5745, #5698) ───────────────────────

    /**
     * The compliance case. `PARTY_ERASED` is emitted once, nothing in this service re-seeds the read
     * model, and acking a failed erasure leaves personal data in a projection after Art. 17 erasure
     * was executed everywhere else. So a persistent failure must reach the platform, not a log line.
     */
    @Test
    fun `a persistent eraseParty failure is rethrown after bounded attempts`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        var attempts = 0
        coEvery { projection.eraseParty(partyId) } answers {
            attempts++
            throw DownstreamDown("read-model DB unreachable")
        }

        assertThatThrownBy {
            runBlocking { consumer.consumePartyEvent("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""") }
        }.isInstanceOf(DownstreamDown::class.java).hasMessage("read-model DB unreachable")

        assertThat(attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
    }

    /** A blip must not cost a nack — otherwise every hiccup becomes a stopped channel. */
    @Test
    fun `a transient eraseParty failure that recovers is retried to success`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        var attempts = 0
        coEvery { projection.eraseParty(partyId) } answers {
            attempts++
            if (attempts < EventRetry.DEFAULT_MAX_ATTEMPTS) throw DownstreamDown("connection reset")
        }

        consumer.consumePartyEvent("""{"eventType":"PARTY_ERASED","partyId":"$partyId"}""")

        assertThat(attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
    }

    /**
     * The projection half: a KYC/SCA transition lost here never reaches the funnel, and the record
     * is acked as done. Also asserts the FAILED counter still fires — the dashboards read it, and a
     * rethrow that skipped it would trade one silent failure for another.
     */
    @Test
    fun `a persistent applyEvent failure is rethrown after bounded attempts`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        var attempts = 0
        coEvery { projection.applyEvent(any()) } answers {
            attempts++
            throw DownstreamDown("read-model DB unreachable")
        }
        val payload = """{"eventType":"DEVICE_ENROLLED","partyId":"$partyId","credentialId":"c1"}"""

        assertThatThrownBy { runBlocking { consumer.consumeScaEvent(payload) } }
            .isInstanceOf(DownstreamDown::class.java)

        assertThat(attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
        verify { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.FAILED) }
    }

    /** Same channel, recovering dependency: retried, projected, no nack. */
    @Test
    fun `a transient applyEvent failure that recovers is retried to success`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        var attempts = 0
        coEvery { projection.applyEvent(any()) } answers {
            attempts++
            if (attempts < EventRetry.DEFAULT_MAX_ATTEMPTS) throw DownstreamDown("connection reset")
            ProjectionResult.APPLIED
        }

        consumer.consumeScaEvent("""{"eventType":"DEVICE_ENROLLED","partyId":"$partyId","credentialId":"c1"}""")

        assertThat(attempts).isEqualTo(EventRetry.DEFAULT_MAX_ATTEMPTS)
        verify { metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED) }
    }

    /**
     * Poison pills stay acked on all three channels, and the projection is never asked. This must
     * hold on BOTH sides of the fix: a blanket rethrow would break it, and no redelivery can help a
     * payload that fails to parse identically forever.
     */
    @Test
    fun `a malformed payload is still acked on every channel and never reaches the projection`(): Unit = runBlocking {
        assertThatCode {
            runBlocking {
                consumer.consumePartyEvent("{not json at all")
                consumer.consumeKycEvent("{not json at all")
                consumer.consumeScaEvent("{not json at all")
                // Well-formed JSON, no usable partyId: also unretryable.
                consumer.consumePartyEvent("""{"eventType":"PARTY_ERASED","partyId":"nope"}""")
            }
        }.doesNotThrowAnyException()

        coVerify(exactly = 0) { projection.eraseParty(any()) }
        coVerify(exactly = 0) { projection.applyEvent(any()) }
    }
}

/** A downstream dependency failing — the transient case, named so the tests read as intent. */
private class DownstreamDown(message: String) : RuntimeException(message)
