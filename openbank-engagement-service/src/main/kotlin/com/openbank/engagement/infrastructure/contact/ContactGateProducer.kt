// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.contact

import com.openbank.engagement.application.port.out.ConsentCheckPort
import com.openbank.engagement.application.port.out.EngagementEventRepository
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID

/**
 * Wires the ADR-0219 gate for engagement-service, same pattern as
 * `campaign-service`'s `ContactGateProducer` (see its KDoc). Two differences that pattern's own
 * comment anticipated:
 *
 * - `impressionsInWindow` is real here, not the `0` campaign-service returns — that comment
 *   said outright "the D1 impression counter arrives with the engagement service, not here."
 * - `suppression` stays the honest empty port `ContactSuppressionPort { emptyList() }`, same as
 *   campaign-service, for the same reason: no ADR-0219 D3 platform suppression *store* exists
 *   yet. This is NOT where the D2 repeated-dismissal exclusion lives — that is a separate, local
 *   check in `ResolveSurfaceUseCase` against this service's own event history, because the
 *   shared `SuppressionReason` vocabulary has no value that describes it (see that use case's
 *   KDoc). `sendsInWindow` is unused by `PROMOTIONAL_IMPRESSION` and returns 0 rather than being
 *   wired to anything invented.
 */
@ApplicationScoped
class ContactGateProducer {

    @Produces
    @ApplicationScoped
    fun contactPolicyGate(
        consentCheck: ConsentCheckPort,
        events: EngagementEventRepository,
        @ConfigProperty(name = "openbank.engagement.impression-budget-per-window", defaultValue = "1")
        impressionBudget: Int,
    ): ContactPolicyGate = ContactPolicyGate(
        consent = ContactConsentPort { partyId, scope -> consentCheck.hasActiveConsent(partyId, scope) },
        counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int = 0
            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int =
                events.impressionsInWindow(partyId, windowStart)
        },
        suppression = ContactSuppressionPort { emptyList() },
        policy = ContactPolicy(impressionBudgetPerWindow = impressionBudget),
    )
}
