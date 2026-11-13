// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.contact

import com.openbank.engagement.application.port.out.ConsentCheckPort
import com.openbank.engagement.application.port.out.EngagementEventRepository
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactDenyReason
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.libs.contact.SuppressionEntry
import com.openbank.libs.contact.SuppressionReason
import com.openbank.libs.contact.SuppressionScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0219 D3/D4 wiring: the produced gate must consult the live suppression list (GDPR Art. 21). */
class ContactGateProducerTest {

    private val consent = mockk<ConsentCheckPort>()
    private val events = mockk<EngagementEventRepository>()
    private val suppressions = mockk<ContactSuppressionPort> {
        coEvery { activeSuppressions(any()) } returns emptyList()
    }
    private val gate = ContactGateProducer().contactPolicyGate(consent, events, suppressions, 1)
    private val partyId: UUID = UUID.randomUUID()

    @Test
    fun `a suppressed party gets no promotional impression and consent is never asked`(): Unit = runBlocking {
        coEvery { suppressions.activeSuppressions(partyId) } returns listOf(
            SuppressionEntry(SuppressionScope.ALL, null, SuppressionReason.CUSTOMER_OPTOUT, "test"),
        )

        val decision = gate.check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, "MARKETING_COMMS_INAPP")

        assertThat(decision.allowed).isFalse()
        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.SUPPRESSED_LIST)
        coVerify(exactly = 0) { consent.hasActiveConsent(any(), any()) }
    }

    @Test
    fun `an unavailable suppression list fails the promotional impression closed`(): Unit = runBlocking {
        coEvery { suppressions.activeSuppressions(partyId) } throws IllegalStateException("consent unavailable")

        val decision = gate.check(partyId, ContactClass.PROMOTIONAL_IMPRESSION, "MARKETING_COMMS_INAPP")

        assertThat(decision.allowed).isFalse()
        assertThat(decision.denyReason).isEqualTo(ContactDenyReason.GATE_UNAVAILABLE)
    }
}
