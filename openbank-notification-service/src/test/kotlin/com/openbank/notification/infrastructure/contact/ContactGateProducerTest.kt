// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.contact

import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactDenyReason
import com.openbank.notification.domain.model.NotificationCategory
import com.openbank.notification.domain.model.NotificationTemplate
import com.openbank.notification.infrastructure.client.ConsentCheckResponse
import com.openbank.notification.infrastructure.client.ConsentServiceClient
import com.openbank.notification.infrastructure.persistence.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The ADR-0219 D4 wiring for this service: what the produced gate's ports actually read.
 *
 * The send counter is the interesting one — it must be scoped to the MARKETING template set, not
 * to every notification. An unscoped count would let a burst of security/transactional messages
 * exhaust a customer's marketing send cap and silently suppress a campaign.
 *
 * Nothing here asserts an ALLOWED decision: `check` consults a real wall-clock quiet-hours window,
 * so an allow assertion would pass or fail by time of day. Every case below is decided before that
 * branch is reached.
 */
class ContactGateProducerTest {

    private val consentClient = mockk<ConsentServiceClient>()
    private val repo = mockk<NotificationRepository>()

    private val gate = ContactGateProducer().contactPolicyGate(consentClient, repo)

    private val partyId: UUID = UUID.randomUUID()

    @Test
    fun `the send counter is scoped to the MARKETING templates only`(): Unit = runBlocking {
        val templates = slot<List<String>>()
        coEvery { repo.countSince(partyId, capture(templates), any()) } returns 99

        gate.check(partyId, ContactClass.OUTBOUND_SEND, "MARKETING_COMMS_EMAIL")

        val expected = NotificationTemplate.entries
            .filter { it.category == NotificationCategory.MARKETING }
            .map { it.name }
        assertThat(templates.captured).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(templates.captured).contains(NotificationTemplate.MARKETING_PRODUCT_OFFER.name)
        assertThat(templates.captured).doesNotContain(
            NotificationTemplate.OTP_CODE.name,
            NotificationTemplate.TRANSACTION_COMPLETED.name,
        )
    }

    @Test
    fun `an exhausted send window denies with SEND_CAP_REACHED and never asks consent`(): Unit = runBlocking {
        coEvery { repo.countSince(any(), any(), any()) } returns 99

        val decision = gate.check(partyId, ContactClass.OUTBOUND_SEND, "MARKETING_COMMS_EMAIL")

        assertThat(decision.allowed).isFalse()
        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.SEND_CAP_REACHED)
        coVerify(exactly = 0) { consentClient.hasActiveConsent(any(), any(), any()) }
    }

    @Test
    fun `a failing send-log read fails CLOSED rather than letting the send through`(): Unit = runBlocking {
        coEvery { repo.countSince(any(), any(), any()) } throws IllegalStateException("db down")

        val decision = gate.check(partyId, ContactClass.OUTBOUND_SEND, "MARKETING_COMMS_EMAIL")

        assertThat(decision.allowed).isFalse()
        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.GATE_UNAVAILABLE)
    }

    @Test
    fun `the impression counter is honestly zero, so only the budget of 1 bounds an impression`(): Unit =
        runBlocking {
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
        coVerify(exactly = 0) { repo.countSince(any(), any(), any()) }
        coVerify(exactly = 0) { consentClient.hasActiveConsent(any(), any(), any()) }
    }

    @Test
    fun `the marketing grantee matches the consent identity the consumer uses`() {
        assertThat(ContactGateProducer.MARKETING_GRANTEE).isEqualTo("party-service:marketing-comms")
    }
}
