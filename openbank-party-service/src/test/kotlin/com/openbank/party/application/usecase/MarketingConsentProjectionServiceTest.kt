// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.out.MarketingConsentTracking
import com.openbank.party.application.port.out.MarketingConsentTrackingRepository
import com.openbank.party.application.port.out.PartyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** ADR-0205 D4: the party-service projection of consent-service's marketing-consent lifecycle. */
class MarketingConsentProjectionServiceTest {

    private val trackingRepository = mockk<MarketingConsentTrackingRepository>()
    private val partyRepository = mockk<PartyRepository>()
    private val service = MarketingConsentProjectionService(trackingRepository, partyRepository)

    private val partyId = UUID.randomUUID()
    private val consentId = UUID.randomUUID()
    private val occurredAt = Instant.parse("2026-03-01T12:00:00Z")

    @Test
    fun `applyGranted upserts tracking and sets consent_marketing true unconditionally`(): Unit = runBlocking {
        coEvery { trackingRepository.upsert(any()) } returns Unit
        coEvery { partyRepository.updateMarketingConsentProjection(any(), any(), any()) } returns Unit

        service.applyGranted(partyId, consentId, occurredAt)

        coVerify(exactly = 1) {
            trackingRepository.upsert(MarketingConsentTracking(partyId, consentId, occurredAt))
        }
        coVerify(exactly = 1) { partyRepository.updateMarketingConsentProjection(partyId, true, occurredAt) }
    }

    @Test
    fun `applyRevokedOrExpired clears the projection when the tracked consent matches`(): Unit = runBlocking {
        coEvery { trackingRepository.deleteIfMatches(partyId, consentId) } returns true
        coEvery { partyRepository.updateMarketingConsentProjection(any(), any(), any()) } returns Unit

        val applied = service.applyRevokedOrExpired(partyId, consentId, occurredAt)

        assertThat(applied).isTrue()
        coVerify(exactly = 1) { partyRepository.updateMarketingConsentProjection(partyId, false, occurredAt) }
    }

    // The load-bearing case: an out-of-order or late-delivered revoke for a consent the party has
    // since re-granted (a DIFFERENT, newer consentId is now tracked) must NOT clear the fresh grant.
    @Test
    fun `applyRevokedOrExpired does nothing when the event's consentId does not match the tracked one`(): Unit =
        runBlocking {
            coEvery { trackingRepository.deleteIfMatches(partyId, consentId) } returns false

            val applied = service.applyRevokedOrExpired(partyId, consentId, occurredAt)

            assertThat(applied).isFalse()
            coVerify(exactly = 0) { partyRepository.updateMarketingConsentProjection(any(), any(), any()) }
        }
}
