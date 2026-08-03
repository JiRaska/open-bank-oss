// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class CampaignStateMachineTest {

    private fun draft() = Campaign(
        id = UUID.randomUUID(),
        name = "Podzimní vklady",
        goal = "Zvýšit vklady",
        segmentRef = SegmentRef("saver-high-balance", 1),
        steps = listOf(CampaignStep(0, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, mapOf("offerTitle" to "5.2 %"), 0)),
        state = CampaignState.DRAFT,
        createdBy = "maker",
        approvedBy = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `draft submits to pending approval`() {
        assertEquals(CampaignState.PENDING_APPROVAL, draft().submit().state)
    }

    @Test
    fun `only pending approval can activate`() {
        assertThrows<IllegalArgumentException> { draft().activate("checker") }
    }

    @Test
    fun `maker cannot approve own campaign - four-eyes domain invariant`() {
        val pending = draft().submit()
        assertThrows<IllegalArgumentException> { pending.activate("maker") }
        assertEquals(CampaignState.ACTIVE, pending.activate("checker").state)
    }

    @Test
    fun `active campaign pauses and resumes, paused closes`() {
        val active = draft().submit().activate("checker")
        assertEquals(CampaignState.PAUSED, active.pause().state)
        assertEquals(CampaignState.ACTIVE, active.pause().resume().state)
        assertEquals(CampaignState.CLOSED, active.pause().close().state)
    }

    @Test
    fun `journeys are capped at five steps`() {
        val sixSteps = (0..5).map { CampaignStep(it, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0) }
        assertThrows<IllegalArgumentException> {
            draft().copy(steps = sixSteps)
        }
    }

    /**
     * The channel set, asserted as a boundary rather than a count.
     *
     * This replaces an assertion that `Channel.valueOf("PUSH")` throws — true while the first slice
     * was EMAIL-only (ADR-0200 D7), and now false: the two blockers that kept push out have cleared
     * (per-channel marketing consent exists as MARKETING_COMMS_PUSH, and #1182 closed by making push
     * bodies generic). What must NOT change is the other half of D7 — a campaign may never be
     * approved against a channel that delivers nothing. SMS has no outbound port anywhere, and
     * IN_APP was removed from notification-service's enum (#2372) because its dispatch branch
     * dropped every message silently.
     */
    @Test
    fun `only channels that actually deliver are representable`() {
        assertEquals(setOf(Channel.EMAIL, Channel.PUSH), Channel.entries.toSet())
        assertThrows<IllegalArgumentException> { Channel.valueOf("SMS") }
        assertThrows<IllegalArgumentException> { Channel.valueOf("IN_APP") }
    }
}
