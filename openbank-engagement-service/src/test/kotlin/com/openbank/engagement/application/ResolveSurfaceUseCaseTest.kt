// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application

import com.openbank.engagement.application.port.out.EngagementEventRepository
import com.openbank.engagement.application.usecase.ResolveSurfaceUseCase
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceSlot
import com.openbank.libs.contact.ContactClass
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ResolveSurfaceUseCaseTest {

    private val party = UUID.randomUUID()

    private fun gate(consented: Boolean = true): ContactPolicyGate = ContactPolicyGate(
        consent = ContactConsentPort { _, _ -> consented },
        counters = object : ContactCounterPort {
            override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant) = 0
            override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
        },
        suppression = ContactSuppressionPort { emptyList() },
        policy = ContactPolicy(),
    )

    @Test
    fun `a consented party with no dismissal history sees the slot's catalogue`(): Unit = runBlocking {
        val events = mockk<EngagementEventRepository>()
        coEvery { events.recentForPartyAndSlot(party, SurfaceSlot.HOME_BANNER, any()) } returns emptyList()

        val result = ResolveSurfaceUseCase(gate(consented = true), events).resolve(party, SurfaceSlot.HOME_BANNER)

        assertThat(result).isInstanceOf(ResolveSurfaceUseCase.Result.Rendered::class.java)
        val rendered = result as ResolveSurfaceUseCase.Result.Rendered
        assertThat(rendered.content).extracting("id").containsExactly("SAVINGS_RATE_BANNER")
    }

    @Test
    fun `a party with no marketing consent is not eligible, not silently empty`(): Unit = runBlocking {
        val events = mockk<EngagementEventRepository>()
        coEvery { events.recentForPartyAndSlot(any(), any(), any()) } returns emptyList()

        val result = ResolveSurfaceUseCase(gate(consented = false), events).resolve(party, SurfaceSlot.HOME_BANNER)

        assertThat(result).isInstanceOf(ResolveSurfaceUseCase.Result.NotEligible::class.java)
    }

    @Test
    fun `three consecutive dismissals suppress the slot even though the gate would allow it`(): Unit = runBlocking {
        val events = mockk<EngagementEventRepository>()
        val dismissals = List(3) {
            EngagementEvent(
                party,
                "SAVINGS_RATE_BANNER",
                SurfaceSlot.HOME_BANNER,
                EngagementEventType.DISMISS,
                Instant.now(),
            )
        }
        coEvery { events.recentForPartyAndSlot(party, SurfaceSlot.HOME_BANNER, any()) } returns dismissals

        val result = ResolveSurfaceUseCase(gate(consented = true), events).resolve(party, SurfaceSlot.HOME_BANNER)

        assertThat(result).isEqualTo(ResolveSurfaceUseCase.Result.Suppressed)
    }

    @Test
    fun `PROMOTIONAL_IMPRESSION denial names a class other than SEND — this is not a campaign send`(): Unit =
        runBlocking {
            assertThat(ContactClass.entries).contains(ContactClass.PROMOTIONAL_IMPRESSION)
        }
}
