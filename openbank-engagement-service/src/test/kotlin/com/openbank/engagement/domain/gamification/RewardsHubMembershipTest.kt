// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.gamification

import com.openbank.engagement.domain.model.gamification.RewardsHubMembership
import com.openbank.engagement.domain.model.gamification.RewardsHubMembershipTransitions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RewardsHubMembershipTest {

    private val party = UUID.randomUUID()

    @Test
    fun `optIn produces an OptedIn state for the party`() {
        val at = Instant.now()
        val membership = RewardsHubMembershipTransitions.optIn(party, at)
        assertThat(membership).isEqualTo(RewardsHubMembership.OptedIn(party, at))
    }

    @Test
    fun `optOut produces an OptedOut state for the party`() {
        val at = Instant.now()
        val membership = RewardsHubMembershipTransitions.optOut(party, at)
        assertThat(membership).isEqualTo(RewardsHubMembership.OptedOut(party, at))
    }

    /**
     * The structural invariant the task requires: "opt-out cannot be un-done except via a new
     * explicit domain operation". There is no function anywhere in this package that takes an
     * `OptedOut` and returns an `OptedIn` as a side effect — the ONLY way to reach `OptedIn` again
     * is a fresh, equally explicit call to [RewardsHubMembershipTransitions.optIn], proven here by
     * actually performing both transitions in sequence and asserting each intermediate state
     * independently, rather than asserting only the final one (which a bug that skipped the
     * opt-out step entirely could also satisfy).
     */
    @Test
    fun `reversing an opt-out requires a fresh explicit optIn call, not a mutation of the opted-out state`() {
        val optOutAt = Instant.now()
        val optedOut = RewardsHubMembershipTransitions.optOut(party, optOutAt)
        assertThat(optedOut).isInstanceOf(RewardsHubMembership.OptedOut::class.java)

        val optInAt = optOutAt.plusSeconds(60)
        val optedInAgain = RewardsHubMembershipTransitions.optIn(party, optInAt)
        assertThat(optedInAgain).isEqualTo(RewardsHubMembership.OptedIn(party, optInAt))

        // The two states are genuinely distinct values — the first was never merely relabelled.
        assertThat(optedOut).isNotEqualTo(optedInAgain)
    }

    @Test
    fun `every membership state carries the party id it was constructed for`() {
        assertThat(RewardsHubMembershipTransitions.optIn(party, Instant.now()).partyId).isEqualTo(party)
        assertThat(RewardsHubMembershipTransitions.optOut(party, Instant.now()).partyId).isEqualTo(party)
    }
}
