// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.usecase

import com.openbank.onboarding.application.port.out.BusinessOnboardingRepository
import com.openbank.onboarding.domain.model.BusinessCaseStage
import com.openbank.onboarding.domain.model.BusinessFunnelStage
import com.openbank.onboarding.domain.model.BusinessOnboardingEvent
import com.openbank.onboarding.domain.model.BusinessOnboardingRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class BusinessOnboardingProjectionServiceTest {

    private val repo = mockk<BusinessOnboardingRepository>()
    private val now = Instant.parse("2026-09-05T10:00:00Z")
    private lateinit var service: BusinessOnboardingProjectionService

    @BeforeEach
    fun setUp() {
        service = BusinessOnboardingProjectionService().apply {
            repo = this@BusinessOnboardingProjectionServiceTest.repo
            clock = Clock.fixed(now, ZoneOffset.UTC)
        }
        coEvery { repo.upsert(any(), any()) } just runs
    }

    @Test
    fun `maps a signed case onto the checks column and keeps the event time`(): Unit = runBlocking {
        coEvery { repo.findByCaseId(any()) } returns null
        val at = Instant.parse("2026-09-05T09:00:00Z")
        val record = slot<BusinessOnboardingRecord>()
        val eventAt = slot<Instant>()

        service.project(event(status = BusinessCaseStage.SIGNED, occurredAt = at))

        coVerify { repo.upsert(capture(record), capture(eventAt)) }
        assertThat(record.captured.stage).isEqualTo(BusinessFunnelStage.AWAITING_CHECKS)
        // createdAt comes from the first event, updatedAt from the clock — a row whose two
        // timestamps are equal would hide how long a case has been sitting.
        assertThat(record.captured.createdAt).isEqualTo(at)
        assertThat(record.captured.updatedAt).isEqualTo(now)
        assertThat(eventAt.captured).isEqualTo(at)
    }

    @Test
    fun `an event that omits register facts does not erase the ones already stored`(): Unit = runBlocking {
        coEvery { repo.findByCaseId(any()) } returns stored()
        val record = slot<BusinessOnboardingRecord>()

        // SIGNER_INVITED carries the case, not the register extract.
        service.project(
            event(status = BusinessCaseStage.AWAITING_COSIGNERS).copy(
                legalName = null,
                legalFormClass = null,
                entityPartyId = null,
                requiredSignatures = null,
            ),
        )

        coVerify { repo.upsert(capture(record), any()) }
        assertThat(record.captured.legalName).isEqualTo("ACME s.r.o.")
        assertThat(record.captured.legalFormClass).isEqualTo("LIMITED_COMPANY")
        assertThat(record.captured.entityPartyId).isEqualTo(ENTITY)
        assertThat(record.captured.requiredSignatures).isEqualTo(2)
        // The first event's createdAt survives; the case did not start again.
        assertThat(record.captured.createdAt).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"))
    }

    @Test
    fun `leaving manual review clears the reason`(): Unit = runBlocking {
        coEvery { repo.findByCaseId(any()) } returns stored().copy(
            caseStatus = BusinessCaseStage.MANUAL_REVIEW,
            stage = BusinessFunnelStage.NEEDS_REVIEW,
            reviewReason = "representation rule could not be parsed",
        )
        val record = slot<BusinessOnboardingRecord>()

        service.project(event(status = BusinessCaseStage.READY_TO_SIGN))

        coVerify { repo.upsert(capture(record), any()) }
        assertThat(record.captured.reviewReason).isNull()
        assertThat(record.captured.stage).isEqualTo(BusinessFunnelStage.AWAITING_SIGNATURES)
    }

    @Test
    fun `a review reason is carried verbatim`(): Unit = runBlocking {
        coEvery { repo.findByCaseId(any()) } returns null
        val reason = "initiator is not listed as a representative in the register"
        val record = slot<BusinessOnboardingRecord>()

        service.project(event(status = BusinessCaseStage.MANUAL_REVIEW).copy(reviewReason = reason))

        coVerify { repo.upsert(capture(record), any()) }
        assertThat(record.captured.reviewReason).isEqualTo(reason)
        assertThat(record.captured.stage).isEqualTo(BusinessFunnelStage.NEEDS_REVIEW)
    }

    @Test
    fun `erasure is delegated to the repository`(): Unit = runBlocking {
        val party = UUID.randomUUID()
        coEvery { repo.anonymizeParty(party) } just runs

        service.eraseParty(party)

        coVerify(exactly = 1) { repo.anonymizeParty(party) }
    }

    private fun event(status: BusinessCaseStage, occurredAt: Instant = now) = BusinessOnboardingEvent(
        eventType = "BUSINESS_ONBOARDING_STARTED",
        caseId = CASE,
        status = status,
        identifierScheme = "ICO",
        identifier = "27074358",
        country = "CZ",
        legalName = "ACME s.r.o.",
        legalFormClass = "LIMITED_COMPANY",
        initiatorPartyId = INITIATOR,
        entityPartyId = ENTITY,
        requiredSignatures = 2,
        signedCount = 1,
        reviewReason = null,
        occurredAt = occurredAt,
    )

    private fun stored() = BusinessOnboardingRecord(
        caseId = CASE,
        identifierScheme = "ICO",
        identifier = "27074358",
        country = "CZ",
        legalName = "ACME s.r.o.",
        legalFormClass = "LIMITED_COMPANY",
        initiatorPartyId = INITIATOR,
        entityPartyId = ENTITY,
        caseStatus = BusinessCaseStage.REGISTRY_VERIFIED,
        stage = BusinessFunnelStage.AWAITING_INITIATOR,
        requiredSignatures = 2,
        signedCount = 0,
        reviewReason = null,
        createdAt = Instant.parse("2026-09-01T08:00:00Z"),
        updatedAt = Instant.parse("2026-09-01T08:00:00Z"),
    )

    private companion object {
        val CASE: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val INITIATOR: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ENTITY: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
