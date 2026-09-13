// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.domain

import com.openbank.campaign.domain.model.Audience
import com.openbank.campaign.domain.model.AudienceState
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AudienceLifecycleTest {

    private fun draft() = Audience(
        segment = Segment("new-savers", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE"))),
        state = AudienceState.DRAFT,
        createdBy = "maker",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `only the maker can submit an audience draft`() {
        assertThrows<IllegalArgumentException> { draft().submit("other") }
        assertEquals(AudienceState.PENDING_APPROVAL, draft().submit("maker").state)
    }

    @Test
    fun `maker cannot approve the submitted audience`() {
        val pending = draft().submit("maker")
        assertThrows<IllegalArgumentException> { pending.approve("maker") }
        assertEquals(AudienceState.APPROVED, pending.approve("checker").state)
    }

    @Test
    fun `approved audience cannot return to the mutable submission path`() {
        val approved = draft().submit("maker").approve("checker")
        assertThrows<IllegalArgumentException> { approved.submit("maker") }
    }
}
