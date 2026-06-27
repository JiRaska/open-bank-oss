// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingRecord
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.infrastructure.client.PartyServiceClient
import com.openbank.onboarding.infrastructure.scheduler.AbandonedRegistrationCleaner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AbandonedRegistrationCleanerTest {

    private lateinit var onboardingRepo: OnboardingRepository
    private lateinit var partyClient: PartyServiceClient
    private lateinit var cleaner: AbandonedRegistrationCleaner

    @BeforeEach
    fun setUp() {
        onboardingRepo = mockk()
        partyClient = mockk()
        cleaner = AbandonedRegistrationCleaner(Clock.systemUTC()).also {
            it.onboardingRepo = onboardingRepo
            it.partyClient = partyClient
        }
    }

    @Test
    fun `expireAbandoned suspends parties stuck longer than 30 days`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val stuckRecord = onboardingRecord(
            partyId = partyId,
            funnelStage = FunnelStage.KYC_OPEN,
            updatedAt = Instant.now().minus(35, ChronoUnit.DAYS),
        )
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns listOf(stuckRecord)
        coEvery { partyClient.suspendParty(partyId, mapOf("kycStatus" to "EXPIRED")) } returns
            Uni.createFrom().item(Response.ok().build())

        cleaner.expireAbandoned()

        coVerify(exactly = 1) { partyClient.suspendParty(partyId, mapOf("kycStatus" to "EXPIRED")) }
    }

    @Test
    fun `expireAbandoned uses EXPIRED not REJECTED as kycStatus`(): Unit = runBlocking {
        // Regression guard: REJECTED is for officer manual rejection; EXPIRED is for system expiry.
        // Using REJECTED would pollute KYC officer audit trail and incorrectly increment rejection metrics.
        val partyId = UUID.randomUUID()
        val stuckRecord = onboardingRecord(
            partyId = partyId,
            funnelStage = FunnelStage.KYC_DOCUMENTS_REQUIRED,
            updatedAt = Instant.now().minus(31, ChronoUnit.DAYS),
        )
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns listOf(stuckRecord)
        coEvery { partyClient.suspendParty(any(), mapOf("kycStatus" to "EXPIRED")) } returns
            Uni.createFrom().item(Response.ok().build())

        cleaner.expireAbandoned()

        coVerify(exactly = 0) { partyClient.suspendParty(any(), mapOf("kycStatus" to "REJECTED")) }
        coVerify(exactly = 1) { partyClient.suspendParty(any(), mapOf("kycStatus" to "EXPIRED")) }
    }

    @Test
    fun `expireAbandoned skips when no stale records found`(): Unit = runBlocking {
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns emptyList()

        cleaner.expireAbandoned()

        coVerify(exactly = 0) { partyClient.suspendParty(any(), any()) }
    }

    @Test
    fun `expireAbandoned continues processing remaining records when one fails`(): Unit = runBlocking {
        val failingPartyId = UUID.randomUUID()
        val successPartyId = UUID.randomUUID()
        val cutoff = Instant.now().minus(35, ChronoUnit.DAYS)
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns listOf(
            onboardingRecord(failingPartyId, FunnelStage.KYC_OPEN, cutoff),
            onboardingRecord(successPartyId, FunnelStage.KYC_OPEN, cutoff),
        )
        coEvery { partyClient.suspendParty(failingPartyId, any()) } throws RuntimeException("Network error")
        coEvery { partyClient.suspendParty(successPartyId, any()) } returns Uni.createFrom().item(Response.ok().build())

        // Should not throw — per-party failures are logged and skipped
        cleaner.expireAbandoned()

        coVerify(exactly = 1) { partyClient.suspendParty(successPartyId, any()) }
    }

    private fun onboardingRecord(partyId: UUID, funnelStage: FunnelStage, updatedAt: Instant) = OnboardingRecord(
        partyId = partyId,
        legalName = "Test Person",
        email = "test@example.com",
        partyStatus = PartyStage.PENDING_KYC,
        kycCaseId = UUID.randomUUID(),
        kycStatus = KycStage.OPEN,
        scaEnrolled = false,
        deviceCount = 0,
        funnelStage = funnelStage,
        blockedReason = null,
        createdAt = updatedAt.minus(1, ChronoUnit.DAYS),
        updatedAt = updatedAt,
    )
}
