// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingRecord
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.infrastructure.client.PartyServiceClient
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * ADR-0237: the abandoned-registration cleanup must publish a liveness heartbeat.
 *
 * "No abandoned registrations found" is both the healthy quiet day and what a schedule that stopped
 * firing looks like from the outside — the `HR000068` class that left five schedulers in this repo
 * never running (#2148, #2187). The heartbeat is the only thing that separates them.
 *
 * The partial-failure test is the deliberate design statement: the heartbeat marks **the sweep's
 * own pass**, not the fate of each party. Gating it on per-item failures would let one permanently
 * unsuspendable party masquerade as a dead scheduler — noise on the control that exists to make a
 * dead scheduler visible is the one thing that hides a dead scheduler.
 */
class AbandonedRegistrationWorkflowLivenessTest {

    private val onboardingRepo = mockk<OnboardingRepository>()
    private val partyClient = mockk<PartyServiceClient>()

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ageOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    private fun successRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    private fun cleaner(metrics: DomainMetrics) = AbandonedRegistrationCleaner(Clock.systemUTC()).also {
        it.onboardingRepo = onboardingRepo
        it.partyClient = partyClient
        it.domainMetrics = metrics
    }

    private fun stuckRecord(partyId: UUID) = OnboardingRecord(
        partyId = partyId,
        legalName = "Test Person",
        email = "test@example.com",
        partyStatus = PartyStage.PENDING_KYC,
        kycCaseId = UUID.randomUUID(),
        kycStatus = KycStage.OPEN,
        scaEnrolled = false,
        deviceCount = 0,
        funnelStage = FunnelStage.KYC_OPEN,
        blockedReason = null,
        createdAt = Instant.now().minus(36, ChronoUnit.DAYS),
        updatedAt = Instant.now().minus(35, ChronoUnit.DAYS),
    )

    @Test
    fun `registers the gauges at startup and records success after a pass`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val partyId = UUID.randomUUID()
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns listOf(stuckRecord(partyId))
        coEvery { partyClient.suspendParty(any(), any()) } returns Uni.createFrom().item(Response.ok().build())

        val cleaner = cleaner(metricsOver(registry))
        cleaner.registerLiveness(StartupEvent())

        // Registered but not yet succeeded. The age gauge is SEEDED AT REGISTRATION (#4208), so a
        // never-run job reads as old as its pod rather than the ~1.8e9 seconds Instant.EPOCH
        // produced — the value that made WorkflowLivenessStale fire 15 minutes after every deploy.
        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        cleaner.expireAbandoned()

        assertThat(successRecordedOf(registry)).isEqualTo(SUCCEEDED)
        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a pass that finds nothing still records success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns emptyList()

        val cleaner = cleaner(metricsOver(registry))
        cleaner.registerLiveness(StartupEvent())
        cleaner.expireAbandoned()

        // The empty early-return is the common case and it IS a successful pass — withholding the
        // heartbeat there would make a permanently healthy job read as stale. Asserted on the
        // success FLAG, not the age: the boot seed already puts the age under the tolerance before
        // expireAbandoned() is called, so an age assertion alone would prove nothing.
        assertThat(successRecordedOf(registry))
            .describedAs("an empty pass still records a success")
            .isEqualTo(SUCCEEDED)
    }

    @Test
    fun `a pass in which an individual suspend failed still records success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } returns listOf(stuckRecord(UUID.randomUUID()))
        coEvery { partyClient.suspendParty(any(), any()) } throws IllegalStateException("party-service 503")

        val cleaner = cleaner(metricsOver(registry))
        cleaner.registerLiveness(StartupEvent())
        cleaner.expireAbandoned()

        // Deliberate: the scheduler ran, and per-item failures are already counted and logged. One
        // party that can never be suspended must not present as a dead schedule.
        assertThat(successRecordedOf(registry))
            .describedAs("per-item failures do not invalidate the sweep's own pass")
            .isEqualTo(SUCCEEDED)
    }

    @Test
    fun `a failure to even list candidates records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { onboardingRepo.listStuckBefore(any(), any()) } throws IllegalStateException("db down")

        val cleaner = cleaner(metricsOver(registry))
        cleaner.registerLiveness(StartupEvent())

        runCatching { cleaner.expireAbandoned() }

        // The sweep never made a pass at all — this is the case the heartbeat exists to expose.
        assertThat(successRecordedOf(registry))
            .describedAs("a sweep that could not run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val WORKFLOW = "onboarding-abandoned-registration-cleanup"
        const val TOLERANCE_SECONDS = 5.0
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
        const val SUCCEEDED = 1.0
    }
}
