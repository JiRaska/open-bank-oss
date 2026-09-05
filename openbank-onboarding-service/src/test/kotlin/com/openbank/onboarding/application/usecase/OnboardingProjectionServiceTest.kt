// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.onboarding.application.port.out.OnboardingRepository
import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.KycStage
import com.openbank.onboarding.domain.model.OnboardingEvent
import com.openbank.onboarding.domain.model.OnboardingRecord
import com.openbank.onboarding.domain.model.PartyStage
import com.openbank.onboarding.domain.model.ProjectionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class OnboardingProjectionServiceTest {

    private val repo = mockk<OnboardingRepository>()
    private lateinit var service: OnboardingProjectionService

    @BeforeEach
    fun setUp() {
        service = OnboardingProjectionService().also { it.repo = repo }
    }

    // ── FunnelStage.derive ────────────────────────────────────────────────────

    @Test
    fun `derive returns REGISTERED for PENDING_KYC with no kyc`(): Unit = runBlocking {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, null, false))
            .isEqualTo(FunnelStage.KYC_OPEN)
    }

    @Test
    fun `derive returns KYC_UNDER_REVIEW when kyc is UNDER_REVIEW`(): Unit = runBlocking {
        assertThat(FunnelStage.derive(PartyStage.PENDING_KYC, KycStage.UNDER_REVIEW, false))
            .isEqualTo(FunnelStage.KYC_UNDER_REVIEW)
    }

    @Test
    fun `derive returns SCA_PENDING for ACTIVE party without device`(): Unit = runBlocking {
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, KycStage.APPROVED, false))
            .isEqualTo(FunnelStage.SCA_PENDING)
    }

    @Test
    fun `derive returns ACTIVE for ACTIVE party with device enrolled`(): Unit = runBlocking {
        assertThat(FunnelStage.derive(PartyStage.ACTIVE, KycStage.APPROVED, true))
            .isEqualTo(FunnelStage.ACTIVE)
    }

    @Test
    fun `derive returns BLOCKED for SUSPENDED party`(): Unit = runBlocking {
        assertThat(FunnelStage.derive(PartyStage.SUSPENDED, KycStage.APPROVED, true))
            .isEqualTo(FunnelStage.BLOCKED)
    }

    // ── applyEvent: PartyCreated ──────────────────────────────────────────────

    @Test
    fun `applyEvent PartyCreated inserts record with REGISTERED stage`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val slot = slot<OnboardingRecord>()
        coEvery { repo.upsert(capture(slot)) } just runs

        coEvery { repo.findByPartyId(any()) } returns null

        service.applyEvent(
            OnboardingEvent.PartyCreated(
                partyId = partyId,
                legalName = "Alice Example",
                email = "alice@example.com",
                occurredAt = Instant.parse("2026-06-01T10:00:00Z"),
            ),
        )

        with(slot.captured) {
            assertThat(this.partyId).isEqualTo(partyId)
            assertThat(legalName).isEqualTo("Alice Example")
            assertThat(partyStatus).isEqualTo(PartyStage.PENDING_KYC)
            assertThat(funnelStage).isEqualTo(FunnelStage.REGISTERED)
            assertThat(scaEnrolled).isFalse()
            assertThat(deviceCount).isEqualTo(0)
        }
        coVerify(exactly = 1) { repo.upsert(any()) }
    }

    // ── applyEvent: KycCaseOpened ─────────────────────────────────────────────

    @Test
    fun `applyEvent KycCaseOpened updates record with KYC_OPEN stage`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val existing = sampleRecord(partyId, partyStatus = PartyStage.PENDING_KYC, funnelStage = FunnelStage.REGISTERED)
        val slot = slot<OnboardingRecord>()
        coEvery { repo.findByPartyId(partyId) } returns existing
        coEvery { repo.upsert(capture(slot)) } just runs

        service.applyEvent(
            OnboardingEvent.KycCaseOpened(
                partyId,
                caseId,
                Instant.parse("2026-06-01T11:00:00Z"),
            ),
        )

        with(slot.captured) {
            assertThat(kycCaseId).isEqualTo(caseId)
            assertThat(kycStatus).isEqualTo(KycStage.OPEN)
            assertThat(funnelStage).isEqualTo(FunnelStage.KYC_OPEN)
        }
    }

    // ── applyEvent: KycStatusChanged → APPROVED ───────────────────────────────

    @Test
    fun `applyEvent KycStatusChanged APPROVED moves to SCA_PENDING`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val existing = sampleRecord(
            partyId,
            kycStatus = KycStage.UNDER_REVIEW,
            funnelStage = FunnelStage.KYC_UNDER_REVIEW,
        )
        val slot = slot<OnboardingRecord>()
        coEvery { repo.findByPartyId(partyId) } returns existing
        coEvery { repo.upsert(capture(slot)) } just runs

        service.applyEvent(
            OnboardingEvent.KycStatusChanged(
                partyId,
                caseId,
                KycStage.APPROVED,
                Instant.parse("2026-06-02T09:00:00Z"),
            ),
        )

        // ACTIVE party + KYC approved + no SCA = SCA_PENDING
        // Note: partyStatus in sampleRecord is ACTIVE
        with(slot.captured) {
            assertThat(kycStatus).isEqualTo(KycStage.APPROVED)
            assertThat(funnelStage).isEqualTo(FunnelStage.SCA_PENDING)
            assertThat(blockedReason).isNull()
        }
    }

    // ── applyEvent: DeviceEnrolled ────────────────────────────────────────────

    @Test
    fun `applyEvent DeviceEnrolled marks scaEnrolled and advances to ACTIVE`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = sampleRecord(
            partyId,
            funnelStage = FunnelStage.SCA_PENDING,
            kycStatus = KycStage.APPROVED,
        )
        val slot = slot<OnboardingRecord>()
        coEvery { repo.findByPartyId(partyId) } returns existing
        coEvery { repo.upsert(capture(slot)) } just runs
        coEvery { repo.recordDeviceEnrolment(partyId, "cred-abc", any()) } returns 1

        service.applyEvent(
            OnboardingEvent.DeviceEnrolled(
                partyId,
                "cred-abc",
                Instant.parse("2026-06-03T08:00:00Z"),
            ),
        )

        with(slot.captured) {
            assertThat(scaEnrolled).isTrue()
            assertThat(deviceCount).isEqualTo(1)
            assertThat(funnelStage).isEqualTo(FunnelStage.ACTIVE)
        }
    }

    // ── applyEvent: the party row does not exist yet (#6248) ──────────────────

    /**
     * No branch may drop an event. Parameterised over all four so a branch added later cannot
     * quietly opt out — `DeviceEnrolled` is the one that was measured losing events, but the
     * other three lose KYC and status transitions the same way, and nothing replays any of them.
     */
    @Test
    fun `applyEvent seeds a record on every branch that needs one, and never drops the event`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val at = Instant.parse("2026-08-19T12:56:36Z")
        val events = listOf(
            OnboardingEvent.DeviceEnrolled(partyId, "cred-abc", at),
            OnboardingEvent.PartyStatusChanged(partyId, PartyStage.ACTIVE, at),
            OnboardingEvent.KycCaseOpened(partyId, UUID.randomUUID(), at),
            OnboardingEvent.KycStatusChanged(partyId, UUID.randomUUID(), KycStage.APPROVED, at),
        )
        val written = mutableListOf<OnboardingRecord>()
        coEvery { repo.findByPartyId(partyId) } returns null
        coEvery { repo.upsert(capture(written)) } just runs
        coEvery { repo.recordDeviceEnrolment(partyId, "cred-abc", any()) } returns 1

        assertThat(events.map { service.applyEvent(it) })
            .describedAs("all four branches seed rather than drop")
            .containsOnly(ProjectionResult.APPLIED_TO_SEEDED_RECORD)

        assertThat(written).describedAs("every event wrote a row").hasSize(4)
        assertThat(written).allSatisfy { assertThat(it.partyId).isEqualTo(partyId) }
        // The seeded row invents no identity it was not given.
        assertThat(written).allSatisfy { assertThat(it.legalName).isNull() }
    }

    /**
     * The other half of the contract: a real update must NOT report a seed. Without this the
     * assertion above passes against a projection that returns APPLIED_TO_SEEDED_RECORD
     * unconditionally.
     */
    @Test
    fun `applyEvent returns APPLIED when the row exists`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.findByPartyId(partyId) } returns sampleRecord(
            partyId,
            funnelStage = FunnelStage.SCA_PENDING,
            kycStatus = KycStage.APPROVED,
        )
        coEvery { repo.upsert(any()) } just runs
        coEvery { repo.recordDeviceEnrolment(partyId, "cred-abc", any()) } returns 1

        val result = service.applyEvent(
            OnboardingEvent.DeviceEnrolled(partyId, "cred-abc", Instant.parse("2026-08-19T12:56:36Z")),
        )

        assertThat(result).isEqualTo(ProjectionResult.APPLIED)
    }

    /**
     * `deviceCount` is whatever the ledger says, never `existing + 1`. Replaying an event the
     * read model has already seen must converge, not inflate — that is what makes a backfill of
     * the enrolments lost to #4353 safe to run more than once.
     */
    @Test
    fun `applyEvent DeviceEnrolled takes deviceCount from the ledger, not from an increment`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = sampleRecord(partyId, funnelStage = FunnelStage.ACTIVE, kycStatus = KycStage.APPROVED)
            .copy(scaEnrolled = true, deviceCount = 7)
        val slot = slot<OnboardingRecord>()
        coEvery { repo.findByPartyId(partyId) } returns existing
        coEvery { repo.upsert(capture(slot)) } just runs
        // The ledger already holds this credential: a replay changes nothing.
        coEvery { repo.recordDeviceEnrolment(partyId, "cred-abc", any()) } returns 7

        service.applyEvent(
            OnboardingEvent.DeviceEnrolled(partyId, "cred-abc", Instant.parse("2026-08-19T12:56:36Z")),
        )

        assertThat(slot.captured.deviceCount)
            .describedAs("a replayed enrolment must not become an 8th device")
            .isEqualTo(7)
    }

    /**
     * `PARTY_CREATED` arriving after an SCA or KYC event must fill in identity, not reset
     * progress. The old branch wrote a fixed `scaEnrolled = false, deviceCount = 0,
     * kycStatus = null, funnelStage = REGISTERED` over whatever the row held.
     */
    @Test
    fun `applyEvent PartyCreated preserves projected state on an existing row`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        val existing = sampleRecord(partyId, funnelStage = FunnelStage.ACTIVE, kycStatus = KycStage.APPROVED)
            .copy(scaEnrolled = true, deviceCount = 2, partyStatus = PartyStage.ACTIVE)
        val slot = slot<OnboardingRecord>()
        coEvery { repo.findByPartyId(partyId) } returns existing
        coEvery { repo.upsert(capture(slot)) } just runs

        service.applyEvent(
            OnboardingEvent.PartyCreated(
                partyId,
                "Jana Nova",
                "jana@example.test",
                Instant.parse("2026-08-19T12:56:12Z"),
            ),
        )

        with(slot.captured) {
            assertThat(legalName).isEqualTo("Jana Nova")
            assertThat(scaEnrolled).describedAs("SCA state survives a late PARTY_CREATED").isTrue()
            assertThat(deviceCount).isEqualTo(2)
            assertThat(kycStatus).isEqualTo(KycStage.APPROVED)
            assertThat(funnelStage).isEqualTo(FunnelStage.ACTIVE)
        }
    }

    // ── getRecord not found ───────────────────────────────────────────────────

    @Test
    fun `getRecord throws when party not found`() {
        val partyId = UUID.randomUUID()
        coEvery { repo.findByPartyId(partyId) } returns null

        assertThatThrownBy {
            runBlocking { service.getRecord(partyId) }
        }.isInstanceOf(OnboardingRecordNotFoundException::class.java)
    }

    // ── funnelCounts ─────────────────────────────────────────────────────────

    @Test
    fun `funnelCounts returns entry for every FunnelStage`(): Unit = runBlocking {
        FunnelStage.entries.forEach { stage ->
            coEvery { repo.countByStage(stage) } returns stage.ordinal.toLong()
        }

        val result = service.funnelCounts()

        assertThat(result.keys).containsExactlyInAnyOrderElementsOf(FunnelStage.entries.map { it.name })
        assertThat(result["ACTIVE"]).isEqualTo(FunnelStage.ACTIVE.ordinal.toLong())
    }

    // ── eraseParty (GDPR Art. 17) ─────────────────────────────────────────────

    @Test
    fun `eraseParty delegates to repository eraseByPartyId`(): Unit = runBlocking {
        val partyId = UUID.randomUUID()
        coEvery { repo.eraseByPartyId(partyId) } just runs

        service.eraseParty(partyId)

        coVerify(exactly = 1) { repo.eraseByPartyId(partyId) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun sampleRecord(
        partyId: UUID = UUID.randomUUID(),
        partyStatus: PartyStage = PartyStage.ACTIVE,
        kycStatus: KycStage? = null,
        funnelStage: FunnelStage = FunnelStage.REGISTERED,
    ) = OnboardingRecord(
        partyId = partyId,
        legalName = "Alice Example",
        email = "alice@example.com",
        partyStatus = partyStatus,
        kycCaseId = null,
        kycStatus = kycStatus,
        scaEnrolled = false,
        deviceCount = 0,
        funnelStage = funnelStage,
        blockedReason = null,
        createdAt = Instant.parse("2026-06-01T10:00:00Z"),
        updatedAt = Instant.parse("2026-06-01T10:00:00Z"),
    )
}
