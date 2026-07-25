// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.MarketingConsentProjectionUseCase
import com.openbank.party.application.port.out.MarketingConsentTracking
import com.openbank.party.application.port.out.MarketingConsentTrackingRepository
import com.openbank.party.application.port.out.PartyRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class MarketingConsentProjectionService(
    private val trackingRepository: MarketingConsentTrackingRepository,
    private val partyRepository: PartyRepository,
) : MarketingConsentProjectionUseCase {

    override suspend fun applyGranted(partyId: UUID, consentId: UUID, occurredAt: Instant) {
        trackingRepository.upsert(MarketingConsentTracking(partyId, consentId, occurredAt))
        partyRepository.updateMarketingConsentProjection(partyId, granted = true, at = occurredAt)
    }

    override suspend fun applyRevokedOrExpired(partyId: UUID, consentId: UUID, occurredAt: Instant): Boolean {
        val applied = trackingRepository.deleteIfMatches(partyId, consentId)
        if (applied) {
            partyRepository.updateMarketingConsentProjection(partyId, granted = false, at = occurredAt)
        }
        return applied
    }
}
