// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.port.`in`

import java.time.Instant
import java.util.UUID

/**
 * Drives the `parties.consent_marketing` projection from consent-service's outbox (ADR-0205 D4).
 * Not part of [PartyUseCase] deliberately — this is a narrow, event-driven projection concern with
 * its own tracking state ([com.openbank.party.application.port.out.MarketingConsentTrackingRepository]),
 * not a party-aggregate command a human or another service invokes directly.
 */
interface MarketingConsentProjectionUseCase {

    /** A marketing consent was granted (or re-granted) for [partyId]. Always applies — a fresh grant wins. */
    suspend fun applyGranted(partyId: UUID, consentId: UUID, occurredAt: Instant)

    /**
     * A marketing consent was revoked or expired. Applies ONLY if [consentId] matches the
     * currently tracked consent for [partyId] — an out-of-order or late-delivered event for a
     * consent already superseded by a fresh grant must not clear the newer one. Returns whether
     * it applied, purely for observability (callers do not need to branch on it).
     */
    suspend fun applyRevokedOrExpired(partyId: UUID, consentId: UUID, occurredAt: Instant): Boolean
}
