// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sca.application.port.`in`.InitiateScaCommand
import com.openbank.sca.application.port.out.DeviceAssertionVerifier
import com.openbank.sca.application.port.out.EnrolledDeviceRepository
import com.openbank.sca.application.port.out.OtpGenerator
import com.openbank.sca.application.port.out.OtpStore
import com.openbank.sca.application.port.out.ScaChallengeRepository
import com.openbank.sca.application.port.out.ScaDecisionStore
import com.openbank.sca.application.port.out.ScaIdempotencyStore
import com.openbank.sca.domain.model.DeviceApprovalDecision
import com.openbank.sca.domain.model.DeviceDecisionType
import com.openbank.sca.domain.model.DynamicLinkingData
import com.openbank.sca.domain.model.ScaChallenge
import com.openbank.sca.domain.model.ScaMethod
import com.openbank.sca.domain.model.ScaPurpose
import com.openbank.sca.domain.model.ScaStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * initiate() must only replay an idempotent challenge while it is still actionable. A spent
 * challenge (consumed, expired, or already carrying a write-once device decision) returned for a
 * fresh attempt makes the next recordDecision() throw ScaChallengeNotAwaitingException -> HTTP
 * 409 — the bug that made a repeated payment (e.g. QRlessPay tablet->mobile) impossible to
 * authorise. Kept in its own class so [ScaServiceTest] stays within the LargeClass budget.
 */
class ScaServiceInitiateIdempotencyTest {

    private val fixedClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(fixedClock)

    private val repository = mockk<ScaChallengeRepository>()
    private val idempotencyStore = mockk<ScaIdempotencyStore>()
    private val decisionStore = mockk<ScaDecisionStore>()
    private val notificationDispatchGuard = mockk<NotificationDispatchGuard>(relaxed = true)

    private lateinit var service: ScaService

    @BeforeEach
    fun setUp() {
        service = ScaService(
            repository = repository,
            otpGenerator = mockk<OtpGenerator>(relaxed = true),
            otpStore = mockk<OtpStore>(relaxed = true),
            notificationDispatchGuard = notificationDispatchGuard,
            idempotencyStore = idempotencyStore,
            enrolledDeviceRepository = mockk<EnrolledDeviceRepository>(relaxed = true),
            decisionStore = decisionStore,
            assertionVerifier = mockk<DeviceAssertionVerifier>(relaxed = true),
            objectMapper = ObjectMapper(),
            metrics = mockk<DomainMetrics>(relaxed = true),
            idempotencyTtlSeconds = 300L,
            clock = fixedClock,
        )
    }

    @Test
    fun `mints a fresh challenge when the idempotent one is already consumed`(): Unit = runBlocking {
        // Previous identical payment succeeded; its challenge is COMPLETED + consumed.
        val partyId = UUID.randomUUID()
        val consumed = challenge(partyId, ScaStatus.COMPLETED).copy(consumedAt = now)
        stubReuseOf(consumed)

        val result = service.initiate(paymentCommand(partyId))

        assertThat(result.id).isNotEqualTo(consumed.id)
        assertThat(result.status).isEqualTo(ScaStatus.PENDING)
        coVerify(exactly = 1) { repository.save(any()) }
        coVerify(exactly = 1) { idempotencyStore.save(any(), any(), 300L) }
    }

    @Test
    fun `mints a fresh challenge when the idempotent one has expired`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val expired = challenge(partyId, expiresAt = now.minusSeconds(1))
        stubReuseOf(expired)

        val result = service.initiate(paymentCommand(partyId))

        assertThat(result.id).isNotEqualTo(expired.id)
        coVerify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `mints a fresh challenge when the idempotent one already has a decision`(): Unit = runBlocking {
        // The first attempt signed (decision recorded, write-once) but the payment never consumed
        // the challenge — it is still PENDING. Replaying it would 409 the next recordDecision().
        val partyId = UUID.randomUUID()
        val pendingDecided = challenge(partyId, ScaStatus.PENDING)
        stubReuseOf(pendingDecided)
        coEvery { decisionStore.find(pendingDecided.id) } returns
            decision(pendingDecided.id, DeviceDecisionType.APPROVED)

        val result = service.initiate(paymentCommand(partyId))

        assertThat(result.id).isNotEqualTo(pendingDecided.id)
        assertThat(result.status).isEqualTo(ScaStatus.PENDING)
        coVerify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `replays the idempotent challenge while it is still actionable`(): Unit = runBlocking {
        // The dedupe guarantee: a rapid double-submit before any decision is recorded shares one
        // challenge (no duplicate authorisation).
        val partyId = UUID.randomUUID()
        val live = challenge(partyId, ScaStatus.PENDING)
        coEvery { idempotencyStore.get(any()) } returns live.id.toString()
        coEvery { repository.findById(live.id) } returns live
        coEvery { decisionStore.find(live.id) } returns null

        val result = service.initiate(paymentCommand(partyId))

        assertThat(result).isEqualTo(live)
        coVerify(exactly = 0) { repository.save(any()) }
        coVerify(exactly = 0) { idempotencyStore.save(any(), any(), any()) }
    }

    private fun stubReuseOf(existing: ScaChallenge) {
        coEvery { idempotencyStore.get(any()) } returns existing.id.toString()
        coEvery { repository.findById(existing.id) } returns existing
        coEvery { decisionStore.find(existing.id) } returns null
        coEvery { repository.save(any()) } answers { firstArg() }
        coEvery { idempotencyStore.save(any(), any(), any()) } returns Unit
    }

    private fun paymentCommand(partyId: UUID) = InitiateScaCommand(
        partyId = partyId,
        purpose = ScaPurpose.PAYMENT_INITIATION,
        preferredMethod = ScaMethod.PUSH_NOTIFICATION,
        dynamicLinkingData = DynamicLinkingData("123", "CZK", "CZ…5399", "Testovaci", null),
        redirectUrl = null,
    )

    private fun decision(challengeId: UUID, type: DeviceDecisionType) = DeviceApprovalDecision(
        challengeId = challengeId,
        credentialId = "cred-1",
        decision = type,
        signatureB64 = "sig",
        decidedAt = now,
    )

    private fun challenge(
        partyId: UUID,
        status: ScaStatus = ScaStatus.PENDING,
        expiresAt: OffsetDateTime = now.plusMinutes(5),
    ) = ScaChallenge(
        id = UUID.randomUUID(),
        partyId = partyId,
        purpose = ScaPurpose.PAYMENT_INITIATION,
        method = ScaMethod.PUSH_NOTIFICATION,
        status = status,
        expiresAt = expiresAt,
        attemptCount = 0,
        maxAttempts = 3,
        dynamicLinkingData = DynamicLinkingData("123", "CZK", "CZ…5399", "Testovaci", null),
        redirectUrl = null,
        createdAt = now.minusMinutes(1),
    )
}
