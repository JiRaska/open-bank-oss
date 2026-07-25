// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.out

import java.time.Instant
import java.util.UUID

/** The currently ACTIVE marketing consent tracked for one party (ADR-0205 D4). */
data class MarketingConsentTracking(val partyId: UUID, val consentId: UUID, val grantedAt: Instant)

/**
 * Outbound persistence port for [MarketingConsentTracking] (ADR-0205 D4) — backs the party-service
 * consumer of consent-service's outbox so a Revoked/Expired event can be matched against the
 * currently tracked consentId rather than trusted blindly (an out-of-order or late-delivered
 * revoke for a superseded consent must not clear a customer's fresh re-grant).
 */
interface MarketingConsentTrackingRepository {

    suspend fun findByPartyId(partyId: UUID): MarketingConsentTracking?

    /** Insert or replace the tracked consent for [partyId] — a fresh grant always wins. */
    suspend fun upsert(tracking: MarketingConsentTracking)

    /**
     * Delete the tracked row for [partyId] only if its consentId equals [consentId] — the
     * match-before-clear guard. Returns true iff a row was deleted (i.e. the event applied).
     */
    suspend fun deleteIfMatches(partyId: UUID, consentId: UUID): Boolean
}
