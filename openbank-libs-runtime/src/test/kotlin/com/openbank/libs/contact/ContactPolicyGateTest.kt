// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.contact

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The full ADR-0219 decision matrix, with scripted state — never a mock framework: the gate is the
 * one place every sender inherits its politeness from, so each deny path, the evaluation order
 * (suppression → caps/quiet → consent), the rolling-window math and the fail-closed rule are
 * pinned here, once, instead of per call site.
 */
class ContactPolicyGateTest {

    private val partyId: UUID = UUID.randomUUID()
    private val scope = "MARKETING_COMMS_EMAIL"

    /** Noon Europe/Prague — safely outside the default 21→8 quiet period. */
    private val noon = Instant.parse("2026-08-03T10:00:00Z")

    private class State {
        var consent = true
        var sends = 0
        var impressions = 0
        var entries = listOf<SuppressionEntry>()
        var failWith: RuntimeException? = null
        var consentCalls = 0
        var counterCalls = 0
        var suppressionCalls = 0
    }

    private fun gate(state: State, now: Instant = noon): ContactPolicyGate {
        val consent = ContactConsentPort { _, _ ->
            state.consentCalls += 1
            state.failWith?.let { throw it }
            state.consent
        }
        val counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int {
                state.counterCalls += 1
                state.failWith?.let { throw it }
                return state.sends
            }

            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant): Int {
                state.counterCalls += 1
                state.failWith?.let { throw it }
                return state.impressions
            }
        }
        val suppression = ContactSuppressionPort {
            state.suppressionCalls += 1
            state.failWith?.let { throw it }
            state.entries
        }
        return ContactPolicyGate(consent, counters, suppression, clock = { now })
    }

    @Test
    fun `allowed when nothing objects`(): Unit = runBlocking {
        val decision = gate(State()).check(partyId, ContactClass.OUTBOUND_SEND, scope)
        assertThat(decision.allowed).isTrue()
    }

    @Test
    fun `suppression wins over everything — even with consent and budget available`(): Unit = runBlocking {
        val state = State().apply {
            entries = listOf(SuppressionEntry(SuppressionScope.ALL, null, SuppressionReason.LEGAL_HOLD, "legal"))
        }
        val decision = gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope)
        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.SUPPRESSED_LIST)
        // Suppression is evaluated first: no counter or consent call may happen after it fires.
        assertThat(state.counterCalls).isZero()
        assertThat(state.consentCalls).isZero()
    }

    @Test
    fun `scope suppression covers only its scope`(): Unit = runBlocking {
        val state = State().apply {
            entries = listOf(
                SuppressionEntry(SuppressionScope.SCOPE, scope, SuppressionReason.CUSTOMER_OPTOUT, "preference-centre"),
            )
        }
        val denied = gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope)
        assertThat(denied.denyReason).isEqualTo(ContactDenyReason.SUPPRESSED_LIST)

        val otherScopeAllowed = gate(state).check(partyId, ContactClass.OUTBOUND_SEND, "MARKETING_COMMS_INAPP")
        assertThat(otherScopeAllowed.allowed).isTrue()
    }

    @Test
    fun `topic suppression covers only its topic`(): Unit = runBlocking {
        val state = State().apply {
            entries =
                listOf(SuppressionEntry(SuppressionScope.TOPIC, "loans", SuppressionReason.RM_MANAGED, "rm-workbench"))
        }
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope, topic = "loans").denyReason)
            .isEqualTo(ContactDenyReason.SUPPRESSED_LIST)
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope, topic = "savings").allowed)
            .isTrue()
        // A null topic can never be topic-suppressed — otherwise one entry would suppress everything.
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope, topic = null).allowed)
            .isTrue()
    }

    @Test
    fun `send cap denies at the limit and only for OUTBOUND_SEND`(): Unit = runBlocking {
        val state = State().apply { sends = 2 }
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope).denyReason)
            .isEqualTo(ContactDenyReason.SEND_CAP_REACHED)
        // An impression does not consume the send cap (ADR-0219 D1 — separate budgets).
        assertThat(gate(state).check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, scope).allowed)
            .isTrue()
    }

    @Test
    fun `impression budget denies at the limit and never touches the send cap`(): Unit = runBlocking {
        val state = State().apply { impressions = 1 }
        assertThat(gate(state).check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, scope).denyReason)
            .isEqualTo(ContactDenyReason.IMPRESSION_BUDGET_REACHED)
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope).allowed)
            .isTrue()
    }

    @Test
    fun `quiet hours deny — including the midnight wrap`(): Unit = runBlocking {
        // 23:30 Europe/Prague (CEST, UTC+2) is 21:30 UTC.
        val night = Instant.parse("2026-08-03T21:30:00Z")
        assertThat(gate(State(), night).check(partyId, ContactClass.OUTBOUND_SEND, scope).denyReason)
            .isEqualTo(ContactDenyReason.QUIET_HOURS)
        // 03:00 Prague is 01:00 UTC — still inside 21→8.
        val earlyMorning = Instant.parse("2026-08-04T01:00:00Z")
        assertThat(gate(State(), earlyMorning).check(partyId, ContactClass.OUTBOUND_SEND, scope).denyReason)
            .isEqualTo(ContactDenyReason.QUIET_HOURS)
        // Impressions carry no quiet period — the customer opened the app.
        assertThat(gate(State(), night).check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, scope).allowed)
            .isTrue()
    }

    @Test
    fun `no consent denies`(): Unit = runBlocking {
        val state = State().apply { consent = false }
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope).denyReason)
            .isEqualTo(ContactDenyReason.NO_CONSENT)
    }

    @Test
    fun `a port failure fails closed for gated classes`(): Unit = runBlocking {
        val state = State().apply { failWith = RuntimeException("valkey down") }
        assertThat(gate(state).check(partyId, ContactClass.OUTBOUND_SEND, scope).denyReason)
            .isEqualTo(ContactDenyReason.GATE_UNAVAILABLE)
        assertThat(gate(state).check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, scope).denyReason)
            .isEqualTo(ContactDenyReason.GATE_UNAVAILABLE)
    }

    @Test
    fun `SERVICE_EXEMPT is never gated and never touches state`(): Unit = runBlocking {
        val state = State().apply { failWith = RuntimeException("everything down") }
        val decision = gate(state).check(partyId, ContactClass.SERVICE_EXEMPT, scope)
        assertThat(decision.allowed).isTrue()
        assertThat(state.suppressionCalls).isZero()
        assertThat(state.counterCalls).isZero()
        assertThat(state.consentCalls).isZero()
    }

    @Test
    fun `the send window start is now minus the full rolling duration`(): Unit = runBlocking {
        val seen = mutableListOf<Instant>()
        val counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int {
                seen += windowStart
                return 0
            }

            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
        }
        val gate = ContactPolicyGate(
            ContactConsentPort { _, _ -> true },
            counters,
            ContactSuppressionPort { emptyList() },
            clock = { noon },
        )
        gate.check(partyId, ContactClass.OUTBOUND_SEND, scope)
        // Rolling, not calendar: the window always spans the full 7 days back from the decision
        // instant — a midnight (or month) boundary inside it resets nothing.
        assertThat(seen).containsExactly(noon.minusSeconds(7L * 24 * 3600))
    }
}
