// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.contact

import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.SendLogRepository
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
 * Wires the ADR-0219 gate for campaign-service (issue #3656 slice 1): consent stays the ADR-0198
 * live per-call check, send counters keep counting from the durable Postgres send log (the
 * cache-flush burst class of ADR-0219 D2 never applied here — there is no cache), and the
 * suppression list is the truthfully-empty port until D3's platform store exists — an honest
 * zero, not a stub that silently stays.
 */
@ApplicationScoped
class ContactGateProducer {

    @Produces
    @ApplicationScoped
    fun contactPolicyGate(
        consentCheck: ConsentCheckPort,
        sendLog: SendLogRepository,
        @ConfigProperty(name = "openbank.campaign.max-sends-per-party-per-week", defaultValue = "2") maxSends: Int,
        @ConfigProperty(name = "openbank.campaign.quiet-hours-start", defaultValue = "21") quietStart: Int,
        @ConfigProperty(name = "openbank.campaign.quiet-hours-end", defaultValue = "8") quietEnd: Int,
    ): ContactPolicyGate = ContactPolicyGate(
        consent = ContactConsentPort { partyId, scope -> consentCheck.hasActiveConsent(partyId, scope) },
        counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int =
                sendLog.countRecentForParty(partyId, windowStart.epochSecond)

            // campaign-service originates no impressions; the D1 impression counter arrives with
            // the engagement service (ADR-0220), not here.
            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int = 0
        },
        suppression = ContactSuppressionPort { emptyList() },
        policy = ContactPolicy(
            sendCapPerWindow = maxSends,
            quietHoursStart = quietStart,
            quietHoursEnd = quietEnd,
        ),
    )
}
