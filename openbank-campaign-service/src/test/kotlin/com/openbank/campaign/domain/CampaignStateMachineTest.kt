// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignDefinition
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.StepCondition
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
        productKind = CampaignProductKind.NONE,
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
    fun `only a draft can be revised`() {
        val revised = draft().revise(
            CampaignDefinition(
                name = "Jarní vklady",
                goal = "Zvýšit spoření",
                productKind = CampaignProductKind.NONE,
                segmentRef = SegmentRef("saver-high-balance", 1),
                steps = listOf(
                    CampaignStep(
                        order = 0,
                        template = "MARKETING_PRODUCT_OFFER",
                        channel = Channel.EMAIL,
                        variables = mapOf("offerTitle" to "4 %"),
                        delaySeconds = 0,
                    ),
                ),
            ),
        )

        assertEquals("Jarní vklady", revised.name)
        assertEquals("maker", revised.createdBy)
        assertThrows<IllegalArgumentException> {
            draft().submit().revise(
                CampaignDefinition(
                    name = "Nesmí projít",
                    goal = "",
                    productKind = CampaignProductKind.NONE,
                    segmentRef = SegmentRef("saver-high-balance", 1),
                    steps = emptyList(),
                ),
            )
        }
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

    @Test
    fun `a decision source must be an earlier existing step with a condition`() {
        val source = CampaignStep(0, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)
        val target = CampaignStep(
            order = 1,
            template = "MARKETING_PRODUCT_OFFER",
            channel = Channel.EMAIL,
            variables = emptyMap(),
            delaySeconds = 0,
            condition = StepCondition.IF_PREVIOUS_CONFIRMED,
            conditionSourceOrder = 0,
        )
        assertEquals(listOf(source, target), draft().copy(steps = listOf(source, target)).steps)

        assertThrows<IllegalArgumentException> {
            draft().copy(steps = listOf(source, target.copy(conditionSourceOrder = 1)))
        }
        assertThrows<IllegalArgumentException> {
            draft().copy(steps = listOf(source, target.copy(condition = null)))
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
        assertEquals(setOf(Channel.EMAIL, Channel.PUSH, Channel.BANNER), Channel.entries.toSet())
        assertThrows<IllegalArgumentException> { Channel.valueOf("SMS") }
        assertThrows<IllegalArgumentException> { Channel.valueOf("IN_APP") }
    }

    // ── ADR-0269 rule 1: the product kind, and when it may still change ──────────────────────

    @Test
    fun `a revision may still change the product kind while the campaign is a DRAFT`() {
        val revised = draft().revise(
            CampaignDefinition(
                name = "Podzimní vklady",
                goal = "Zvýšit vklady",
                productKind = CampaignProductKind.UNSECURED,
                segmentRef = SegmentRef("saver-high-balance", 1),
                steps = draft().steps,
            ),
        )
        assertEquals(CampaignProductKind.UNSECURED, revised.productKind)
    }

    @Test
    fun `the product kind is frozen once the campaign leaves DRAFT`() {
        // Fixed at publish, and in fact earlier: submission is the point of no return. A kind that
        // could change under an ACTIVE campaign would change which consent governs the parties it
        // has already enrolled, retroactively.
        val submitted = draft().submit()
        assertThrows<IllegalArgumentException> {
            submitted.revise(
                CampaignDefinition(
                    name = submitted.name,
                    goal = submitted.goal,
                    productKind = CampaignProductKind.REVOLVING,
                    segmentRef = submitted.segmentRef,
                    steps = submitted.steps,
                ),
            )
        }
    }

    @Test
    fun `only NONE is not credit`() {
        // The gate branches on isCredit, so a new member added to the enum later must be a
        // deliberate decision about which side of that line it falls on, not a default.
        assertEquals(false, CampaignProductKind.NONE.isCredit)
        CampaignProductKind.entries.filter { it != CampaignProductKind.NONE }
            .forEach { assertEquals(true, it.isCredit, "$it must count as credit") }
    }
}
