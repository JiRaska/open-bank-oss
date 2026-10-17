// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.contact

import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactDenyReason
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.libs.contact.SuppressionEntry
import com.openbank.libs.contact.SuppressionReason
import com.openbank.libs.contact.SuppressionScope
import com.openbank.notification.infrastructure.client.ConsentCheckResponse
import com.openbank.notification.infrastructure.client.ConsentServiceClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The ADR-0219 D4 wiring for this service: what the produced gate's ports actually read.
 *
 * Nothing here asserts an ALLOWED decision: `check` consults a real wall-clock quiet-hours window,
 * so an allow assertion would pass or fail by time of day. Every case below is decided before that
 * branch is reached.
 */
class ContactGateProducerTest {

    private val consentClient = mockk<ConsentServiceClient>()
    private val suppressions = mockk<ContactSuppressionPort> {
        coEvery { activeSuppressions(any()) } returns emptyList()
    }

    private val gate = ContactGateProducer().contactPolicyGate(consentClient, suppressions)

    private val partyId: UUID = UUID.randomUUID()

    @Test
    fun `a platform suppression denies notification marketing before counters and consent`(): Unit = runBlocking {
        coEvery { suppressions.activeSuppressions(partyId) } returns listOf(
            SuppressionEntry(SuppressionScope.ALL, null, SuppressionReason.CUSTOMER_OPTOUT, "test"),
        )

        val decision = gate.check(partyId, ContactClass.OUTBOUND_SEND, "MARKETING_COMMS_EMAIL")

        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.SUPPRESSED_LIST)
        coVerify(exactly = 0) { consentClient.hasActiveConsent(any(), any(), any()) }
    }

    @Test
    fun `unavailable suppression list fails notification marketing closed`(): Unit = runBlocking {
        coEvery { suppressions.activeSuppressions(partyId) } throws IllegalStateException("consent unavailable")

        val decision = gate.check(partyId, ContactClass.OUTBOUND_SEND, "MARKETING_COMMS_EMAIL")

        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.GATE_UNAVAILABLE)
    }

    @Test
    fun `the impression counter is honestly zero, so only the budget of 1 bounds an impression`(): Unit = runBlocking {
        // `impressionsInWindow` is wired to 0 rather than to something invented, so the first
        // impression is decided by consent — reached only because 0 is under the budget.
        every {
            consentClient.hasActiveConsent(partyId, ContactGateProducer.MARKETING_GRANTEE, "MARKETING_COMMS_PUSH")
        } returns Uni.createFrom().item(ConsentCheckResponse(granted = false))

        val decision = gate.check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, "MARKETING_COMMS_PUSH")

        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.NO_CONSENT)
    }

    @Test
    fun `a SERVICE_EXEMPT contact is allowed without touching consent or the send log`(): Unit = runBlocking {
        val decision = gate.check(partyId, ContactClass.SERVICE_EXEMPT, "MARKETING_COMMS_EMAIL")

        assertThat(decision.allowed).isTrue()
        coVerify(exactly = 0) { consentClient.hasActiveConsent(any(), any(), any()) }
    }

    @Test
    fun `the marketing grantee matches the consent identity the consumer uses`() {
        assertThat(ContactGateProducer.MARKETING_GRANTEE).isEqualTo("party-service:marketing-comms")
    }
}
