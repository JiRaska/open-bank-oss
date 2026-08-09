// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.CreatePartyCommand
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * Onboarding consent capture (mobile app "Agreement" step, ADR-0069). Split out of
 * [PartyServiceTest] to keep that class under detekt's LargeClass threshold.
 */
class PartyServiceConsentTest {

    private val now = Instant.parse("2025-01-01T00:00:00Z")

    private fun newService() = PartyService().apply {
        partyRepo = mockk()
        documentRepo = mockk()
        documentFileRepo = mockk()
        metrics = mockk(relaxed = true)
        gdprAggregation = mockk(relaxed = true)
        rcPepper = Optional.empty()
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    @Test
    fun `createParty stores consent flags and stamps consentCapturedAt when either is present`(): Unit = runBlocking {
        val service = newService()
        val savedSlot = slot<Party>()
        val eventSlot = slot<PartyEvent>()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        coEvery { service.partyRepo.save(capture(savedSlot), capture(eventSlot)) } answers { savedSlot.captured }

        service.createParty(
            CreatePartyCommand(
                idempotencyKey = "key3",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Test3",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = null,
                registrationNumber = null,
                email = "t3@example.com",
                phone = null,
                address = null,
                consentGdpr = true,
                consentMarketing = false,
            ),
        )

        assertThat(savedSlot.captured.consentGdpr).isTrue()
        assertThat(savedSlot.captured.consentMarketing).isFalse()
        assertThat(savedSlot.captured.consentCapturedAt).isEqualTo(now)
    }

    @Test
    fun `createParty leaves consent fields and consentCapturedAt null when neither is sent`(): Unit = runBlocking {
        val service = newService()
        val savedSlot = slot<Party>()
        val eventSlot = slot<PartyEvent>()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        coEvery { service.partyRepo.save(capture(savedSlot), capture(eventSlot)) } answers { savedSlot.captured }

        service.createParty(
            CreatePartyCommand(
                idempotencyKey = "key4",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Test4",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = null,
                registrationNumber = null,
                email = "t4@example.com",
                phone = null,
                address = null,
            ),
        )

        assertThat(savedSlot.captured.consentGdpr).isNull()
        assertThat(savedSlot.captured.consentMarketing).isNull()
        assertThat(savedSlot.captured.consentCapturedAt).isNull()
    }
}
