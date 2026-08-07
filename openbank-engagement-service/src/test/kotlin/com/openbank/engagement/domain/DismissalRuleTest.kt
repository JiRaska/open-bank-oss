// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain

import com.openbank.engagement.domain.model.DismissalRule
import com.openbank.engagement.domain.model.EngagementEvent
import com.openbank.engagement.domain.model.EngagementEventType
import com.openbank.engagement.domain.model.SurfaceSlot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DismissalRuleTest {

    private val party = UUID.randomUUID()

    private fun event(type: EngagementEventType) = EngagementEvent(
        partyId = party,
        contentId = "SAVINGS_RATE_BANNER",
        slot = SurfaceSlot.HOME_BANNER,
        type = type,
        occurredAt = Instant.now(),
    )

    @Test
    fun `two dismissals do not suppress`() {
        val events = listOf(event(EngagementEventType.DISMISS), event(EngagementEventType.DISMISS))
        assertThat(DismissalRule.shouldSuppress(events)).isFalse
    }

    @Test
    fun `three consecutive dismissals suppress`() {
        val events = List(3) { event(EngagementEventType.DISMISS) }
        assertThat(DismissalRule.shouldSuppress(events)).isTrue
    }

    @Test
    fun `a click resets the dismissal count`() {
        val events = listOf(
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.CLICK),
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.DISMISS),
        )
        // Two dismissals, a click, then two more — never three consecutive.
        assertThat(DismissalRule.shouldSuppress(events)).isFalse
    }

    @Test
    fun `a conversion resets the dismissal count`() {
        val events = listOf(
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.CONVERSION),
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.DISMISS),
        )
        assertThat(DismissalRule.shouldSuppress(events)).isFalse
    }

    @Test
    fun `an impression neither resets nor advances the count`() {
        val events = listOf(
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.IMPRESSION),
            event(EngagementEventType.DISMISS),
            event(EngagementEventType.IMPRESSION),
            event(EngagementEventType.DISMISS),
        )
        // Three dismissals total, none adjacent-broken by a click/conversion — still consecutive
        // in the sense the rule cares about, since impressions are neutral, not resetting.
        assertThat(DismissalRule.shouldSuppress(events)).isTrue
    }

    @Test
    fun `no events do not suppress`() {
        assertThat(DismissalRule.shouldSuppress(emptyList())).isFalse
    }
}
