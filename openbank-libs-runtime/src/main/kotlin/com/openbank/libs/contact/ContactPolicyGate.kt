// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.contact

import org.jboss.logging.Logger
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Live consent state (ADR-0198/0195): a cached consent survives its own revocation. */
fun interface ContactConsentPort {
    suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean
}

/**
 * Counted contacts in a rolling window. Slice 1 is backed by each sender's durable log (the
 * campaign send log is Postgres); ADR-0219 D2's shared Valkey counters with rebuild-on-flush are
 * a follow-up — this port is where they plug in without a call-site change.
 */
interface ContactCounterPort {
    suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int
    suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int
}

/** The platform suppression list (ADR-0219 D3). */
fun interface ContactSuppressionPort {
    suspend fun activeSuppressions(partyId: UUID): List<SuppressionEntry>
}

/**
 * The ADR-0219 gate: every customer-originating touch passes here (D4 call sites:
 * notification choke point, campaign journeys, agent-proposed contact, engagement surfaces,
 * RM-initiated sends). One call wraps suppression → caps/quiet period → consent, in the D3/D6
 * ordering — "a suppression is not a consent question", and a customer who consented can still
 * be someone the bank must not contact about a specific topic today.
 *
 * Fail-closed (D5): any port failure denies OUTBOUND_SEND and PROMOTIONAL_IMPRESSION with
 * [ContactDenyReason.GATE_UNAVAILABLE]; SERVICE_EXEMPT never touches a port and never denies.
 */
class ContactPolicyGate(
    private val consent: ContactConsentPort,
    private val counters: ContactCounterPort,
    private val suppression: ContactSuppressionPort,
    private val policy: ContactPolicy = ContactPolicy(),
    private val clock: () -> Instant = { Instant.now() },
) {

    private val log = Logger.getLogger(ContactPolicyGate::class.java)

    @Suppress("TooGenericExceptionCaught")
    suspend fun check(
        partyId: UUID,
        contactClass: ContactClass,
        consentScope: String,
        topic: String? = null,
    ): ContactGateDecision {
        if (contactClass == ContactClass.SERVICE_EXEMPT) return ContactGateDecision.ALLOWED
        return try {
            evaluate(partyId, contactClass, consentScope, topic)
        } catch (ex: Exception) {
            log.warnf(
                "contact gate state unavailable for %s/%s — failing closed: %s",
                partyId,
                contactClass,
                ex.message,
            )
            ContactGateDecision.denied(ContactDenyReason.GATE_UNAVAILABLE)
        }
    }

    private suspend fun evaluate(
        partyId: UUID,
        contactClass: ContactClass,
        consentScope: String,
        topic: String?,
    ): ContactGateDecision {
        // D3 before consent (ADR-0200 D6 ordering): a suppression is not a consent question.
        if (suppression.activeSuppressions(partyId).any { it.covers(consentScope, topic) }) {
            return ContactGateDecision.denied(ContactDenyReason.SUPPRESSED_LIST)
        }
        val now = clock()
        return when (contactClass) {
            ContactClass.SERVICE_EXEMPT -> ContactGateDecision.ALLOWED
            ContactClass.OUTBOUND_SEND -> {
                val windowStart = now.minusSeconds(policy.sendWindowSeconds)
                if (counters.sendsInWindow(partyId, windowStart) >= policy.sendCapPerWindow) {
                    return ContactGateDecision.denied(ContactDenyReason.SEND_CAP_REACHED)
                }
                if (isQuietHours(now)) {
                    return ContactGateDecision.denied(ContactDenyReason.QUIET_HOURS)
                }
                if (!consent.hasActiveConsent(partyId, consentScope)) {
                    return ContactGateDecision.denied(ContactDenyReason.NO_CONSENT)
                }
                ContactGateDecision.ALLOWED
            }
            ContactClass.PROMOTIONAL_IMPRESSION -> {
                // Impressions carry their own budget (D1) — never the send cap — and no quiet
                // period: the customer opened the app; degradation is content, never functionality.
                val windowStart = now.minusSeconds(policy.impressionWindowSeconds)
                if (counters.impressionsInWindow(partyId, windowStart) >= policy.impressionBudgetPerWindow) {
                    return ContactGateDecision.denied(ContactDenyReason.IMPRESSION_BUDGET_REACHED)
                }
                if (!consent.hasActiveConsent(partyId, consentScope)) {
                    return ContactGateDecision.denied(ContactDenyReason.NO_CONSENT)
                }
                ContactGateDecision.ALLOWED
            }
        }
    }

    /** Quiet hours wrap midnight: 21→8 means "hour >= 21 OR hour < 8" in the platform zone. */
    private fun isQuietHours(now: Instant): Boolean {
        val hour = now.atZone(ZoneId.of(policy.quietZone)).hour
        return if (policy.quietHoursStart > policy.quietHoursEnd) {
            hour >= policy.quietHoursStart || hour < policy.quietHoursEnd
        } else {
            hour >= policy.quietHoursStart && hour < policy.quietHoursEnd
        }
    }
}
