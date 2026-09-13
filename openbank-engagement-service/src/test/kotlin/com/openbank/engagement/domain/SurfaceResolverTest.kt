// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain

import com.openbank.engagement.domain.model.AdverseState
import com.openbank.engagement.domain.model.EligibilitySnapshot
import com.openbank.engagement.domain.model.SurfaceResolver
import com.openbank.engagement.domain.model.SurfaceSlot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SurfaceResolverTest {

    private fun snapshot(adverseState: Set<AdverseState> = emptySet()) =
        EligibilitySnapshot(partyId = UUID.randomUUID(), adverseState = adverseState, asOf = Instant.now())

    @Test
    fun `an eligible party sees the slot's catalogue entries`() {
        val resolved = SurfaceResolver.resolve(SurfaceSlot.HOME_BANNER, snapshot())
        assertThat(resolved).extracting("id").containsExactly("SAVINGS_RATE_BANNER")
    }

    @Test
    fun `a fraud-hold party sees nothing, not a fallback`() {
        val resolved = SurfaceResolver.resolve(
            SurfaceSlot.HOME_BANNER,
            snapshot(setOf(AdverseState.FRAUD_HOLD)),
        )
        assertThat(resolved).isEmpty()
    }

    @Test
    fun `a slot with no catalogue entries resolves empty for an eligible party too`() {
        // Distinguishes "nothing catalogued" from "excluded" — both are empty, but for a different
        // reason, and neither is a bug: STORIES has no content yet. (REWARDS_HUB is no longer this
        // case as of ADR-0220 D3's gamification slice — see SurfaceCatalogTest.)
        assertThat(SurfaceResolver.resolve(SurfaceSlot.STORIES, snapshot())).isEmpty()
    }
}
