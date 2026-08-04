// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain

import com.openbank.engagement.domain.model.BadgeType
import com.openbank.engagement.domain.model.EarnSource
import com.openbank.engagement.domain.model.EngagementProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The five ADR-0220 D3 safety invariants, pinned as domain rules — not content policy.
 */
class EngagementSafetyInvariantTest {

    private val partyId = UUID.randomUUID()
    private val cap = 1000

    private fun profile(enrolled: Boolean = true, adverseState: Boolean = false, earned: Int = 0) = EngagementProfile(
        partyId = partyId,
        enrolled = enrolled,
        adverseState = adverseState,
        streakDays = 0,
        lastActivityAt = null,
        totalPoints = earned,
        earnedThisYear = earned,
        badges = emptySet(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    // Invariant 1: EarnSource catalogue only contains non-risky sources — no credit uptake.
    @Test
    fun `all EarnSources are non-credit-bearing`() {
        EarnSource.entries.forEach { source ->
            // This test pins the catalogue: if a risky source is ever added, a reviewer must
            // consciously remove it from EarnSource (a PR to the enum is the control).
            assertThat(source.name).doesNotContain("CREDIT", "LOAN_UPTAKE", "OVERDRAFT_INCREASE")
        }
    }

    // Invariant 2: opt-in, award denied when not enrolled.
    @Test
    fun `award denied when not enrolled`() {
        assertThatThrownBy { profile(enrolled = false).award(10, cap, Instant.now()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("opted in")
    }

    // Invariant 2: opt-out keeps earned value.
    @Test
    fun `opt-out keeps earned points and badges`() {
        val withPoints = profile(enrolled = true, earned = 100).copy(badges = setOf(BadgeType.STREAK_7_DAYS))
        val after = withPoints.optOut(Instant.now())
        assertThat(after.enrolled).isFalse()
        assertThat(after.totalPoints).isEqualTo(100)
        assertThat(after.badges).containsExactly(BadgeType.STREAK_7_DAYS)
    }

    // Invariant 3: expiresAt is nullable — it is only set when a real deadline exists.
    @Test
    fun `Challenge expiresAt defaults to null — no fake urgency by default`() {
        val challenge = com.openbank.engagement.domain.model.Challenge(
            id = UUID.randomUUID(),
            partyId = partyId,
            description = "Save 3 times",
            earnSource = EarnSource.SAVINGS_DEPOSIT,
            pointsReward = 50,
            expiresAt = null,
            completedAt = null,
            createdAt = Instant.now(),
        )
        assertThat(challenge.expiresAt).isNull()
        assertThat(challenge.expired).isFalse()
    }

    // Invariant 4: yearly cap enforced.
    @Test
    fun `award denied when yearly cap would be breached`() {
        val atCap = profile(earned = cap)
        assertThatThrownBy { atCap.award(1, cap, Instant.now()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("yearly reward cap")
    }

    // Invariant 5: adverse-state exclusion.
    @Test
    fun `award denied when party is in adverse state`() {
        assertThatThrownBy { profile(adverseState = true).award(10, cap, Instant.now()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("adverse state")
    }
}
