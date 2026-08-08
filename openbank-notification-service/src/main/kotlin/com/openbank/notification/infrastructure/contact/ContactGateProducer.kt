// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.contact

import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.notification.domain.model.NotificationCategory
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.infrastructure.client.ConsentServiceClient
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
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
 * - `counters.sendsInWindow`: [NotificationRepository.countSince], scoped to the MARKETING
 *   template set — this service's own durable send log, same "slice 1" convention as
 *   campaign-service's send log (no shared Valkey counter exists yet).
 *   `impressionsInWindow` is unused by `OUTBOUND_SEND` and returns 0 rather than being wired to
 *   anything invented.
 * - `suppression`: the honest empty port, same reason as campaign/engagement — no ADR-0219 D3
 *   platform suppression *store* exists yet.
 */
@ApplicationScoped
class ContactGateProducer {

    @Produces
    @ApplicationScoped
    fun contactPolicyGate(
        @RestClient consentServiceClient: ConsentServiceClient,
        notificationRepo: NotificationRepository,
    ): ContactPolicyGate {
        val marketingTemplates = NotificationTemplate.entries
            .filter { it.category == NotificationCategory.MARKETING }
            .map { it.name }
        return ContactPolicyGate(
            consent = ContactConsentPort { partyId, scope ->
                consentServiceClient.hasActiveConsent(partyId, MARKETING_GRANTEE, scope).awaitSuspending().granted
            },
            counters = object : ContactCounterPort {
                override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int =
                    notificationRepo.countSince(partyId, marketingTemplates, windowStart)

                override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int = 0
            },
            suppression = ContactSuppressionPort { emptyList() },
            policy = ContactPolicy(),
        )
    }

    companion object {
        /** Matches `NotificationConsumer.MARKETING_GRANTEE` (ADR-0205 D3). */
        const val MARKETING_GRANTEE = "party-service:marketing-comms"
    }
}
