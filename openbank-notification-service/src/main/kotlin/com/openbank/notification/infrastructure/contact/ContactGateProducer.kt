// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.contact

import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.notification.infrastructure.client.ConsentServiceClient
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.time.Instant
import java.util.UUID

/**
 * Wires the ADR-0219 gate for notification-service — the D4 choke point named explicitly
 * ("its consent call becomes this gate call"), same producer pattern as campaign-service's and
 * engagement-service's own `ContactGateProducer` (see their KDoc for the shared shape).
 *
 * - `consent`: the same `ConsentServiceClient` call `NotificationConsumer.gateMarketingOnConsent`
 *   used directly before this change.
 * - The gate handles live suppression, quiet hours and consent. Its read-only counter is zero;
 *   the actual send cap is enforced by [MarketingContactReservationStore] atomically before
 *   provider handoff. Counting the newly persisted PENDING row here would deny the current
 *   contact against itself, while a read-only count cannot serialize concurrent sends.
 * - `suppression`: the live consent-service list, shared with campaign-service. An unavailable
 *   read propagates so the gate denies counted traffic with GATE_UNAVAILABLE.
 */
@ApplicationScoped
class ContactGateProducer {

    @Produces
    @ApplicationScoped
    fun contactPolicyGate(
        @RestClient consentServiceClient: ConsentServiceClient,
        suppression: ContactSuppressionPort,
    ): ContactPolicyGate = ContactPolicyGate(
        consent = ContactConsentPort { partyId, scope ->
            consentServiceClient.hasActiveConsent(partyId, MARKETING_GRANTEE, scope).awaitSuspending().granted
        },
        counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int = 0

            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int = 0
        },
        suppression = suppression,
        policy = ContactPolicy(),
    )

    companion object {
        /** Matches `NotificationConsumer.MARKETING_GRANTEE` (ADR-0205 D3). */
        const val MARKETING_GRANTEE = "party-service:marketing-comms"
    }
}
