// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.UpdateMarketingConsentCommand
import com.openbank.party.application.port.out.MarketingConsentForwardingException
import com.openbank.party.application.port.out.MarketingConsentTracking
import com.openbank.party.domain.model.Address
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Post-onboarding marketing-consent toggle (mobile app Profile screen). Split into its own class
 * to keep [PartyServiceTest] under detekt's LargeClass threshold.
 *
 * ADR-0198 D3 / ADR-0206 D5: [PartyService.updateMarketingConsent] now FORWARDS to consent-service
 * instead of writing `consentMarketing`/calling `publishPartyUpdated` itself —
 * [MarketingConsentProjectionService] (ADR-0205 D4) is the sole writer of that column, driven by
 * consent-service's own outbox events. These tests replace the pre-ADR-0206 versions that asserted
 * a direct `partyRepo.update()` write.
 */
class PartyServiceMarketingConsentTest {

    private val now = Instant.parse("2026-03-01T12:00:00Z")
    private val partyId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun newService() = PartyService().apply {
        partyRepo = mockk()
        documentRepo = mockk()
        documentFileRepo = mockk()
        metrics = mockk(relaxed = true)
        gdprAggregation = mockk(relaxed = true)
        marketingConsentForwarding = mockk()
        marketingConsentTracking = mockk()
        rcPepper = Optional.empty()
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun existingParty(consentMarketing: Boolean?, consentMarketingUpdatedAt: Instant? = null) = Party(
        id = partyId,
        partyType = PartyType.INDIVIDUAL,
        status = PartyStatus.ACTIVE,
        legalName = "Test Party",
        tradingName = null,
        dateOfBirth = null,
        nationality = null,
        taxId = null,
        registrationNumber = null,
        email = "test@example.com",
        phone = null,
        address = null as Address?,
        kycStatus = KycStatus.APPROVED,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        amlStatus = AmlStatus.CLEARED,
        consentGdpr = true,
        consentCapturedAt = Instant.parse("2026-01-01T00:00:00Z"),
        consentMarketing = consentMarketing,
        consentMarketingUpdatedAt = consentMarketingUpdatedAt,
    )

    @Test
    fun `updateMarketingConsent true forwards a grant and does not write partyRepo`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns existingParty(consentMarketing = false)
        coEvery { service.marketingConsentForwarding.grant(partyId) } returns UUID.randomUUID()

        val result = service.updateMarketingConsent(UpdateMarketingConsentCommand(partyId, marketingConsent = true))

        assertThat(result.consentMarketing).isTrue()
        assertThat(result.consentMarketingUpdatedAt).isEqualTo(now)
        // The immutable onboarding snapshot must not be touched by a later grant.
        assertThat(result.consentGdpr).isTrue()
        assertThat(result.consentCapturedAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        coVerify(exactly = 1) { service.marketingConsentForwarding.grant(partyId) }
        coVerify(exactly = 0) { service.partyRepo.update(any()) }
        coVerify(exactly = 0) { service.partyRepo.update(any(), any()) }
    }

    @Test
    fun `updateMarketingConsent false forwards a revoke using the tracked consentId`(): Unit = runBlocking {
        val service = newService()
        val trackedConsentId = UUID.randomUUID()
        coEvery { service.partyRepo.findById(partyId) } returns existingParty(consentMarketing = true)
        coEvery { service.marketingConsentTracking.findByPartyId(partyId) } returns
            MarketingConsentTracking(partyId, trackedConsentId, now)
        coJustRun { service.marketingConsentForwarding.revoke(partyId, trackedConsentId, any()) }

        val result = service.updateMarketingConsent(UpdateMarketingConsentCommand(partyId, marketingConsent = false))

        assertThat(result.consentMarketing).isFalse()
        assertThat(result.consentMarketingUpdatedAt).isEqualTo(now)
        coVerify(exactly = 1) { service.marketingConsentForwarding.revoke(partyId, trackedConsentId, any()) }
        coVerify(exactly = 0) { service.partyRepo.update(any()) }
    }

    @Test
    fun `updateMarketingConsent propagates a forwarding failure on revoke instead of swallowing it`() {
        val service = newService()
        val trackedConsentId = UUID.randomUUID()
        coEvery { service.partyRepo.findById(partyId) } returns existingParty(consentMarketing = true)
        coEvery { service.marketingConsentTracking.findByPartyId(partyId) } returns
            MarketingConsentTracking(partyId, trackedConsentId, now)
        coEvery { service.marketingConsentForwarding.revoke(partyId, trackedConsentId, any()) } throws
            MarketingConsentForwardingException("consent-service unreachable")

        assertThatThrownBy {
            runBlocking {
                service.updateMarketingConsent(UpdateMarketingConsentCommand(partyId, marketingConsent = false))
            }
        }.isInstanceOf(MarketingConsentForwardingException::class.java)
    }

    @Test
    fun `updateMarketingConsent false is a no-op forward when nothing is tracked`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns existingParty(consentMarketing = false)
        coEvery { service.marketingConsentTracking.findByPartyId(partyId) } returns null

        val result = service.updateMarketingConsent(UpdateMarketingConsentCommand(partyId, marketingConsent = false))

        assertThat(result.consentMarketing).isFalse()
        coVerify(exactly = 0) { service.marketingConsentForwarding.revoke(any(), any(), any()) }
    }

    @Test
    fun `updateMarketingConsent throws PartyNotFoundException for an unknown id`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns null

        assertThatThrownBy {
            runBlocking { service.updateMarketingConsent(UpdateMarketingConsentCommand(partyId, true)) }
        }.isInstanceOf(PartyNotFoundException::class.java)
    }
}
