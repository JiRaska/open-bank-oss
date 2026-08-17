// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.gamification

import com.openbank.engagement.domain.model.gamification.Challenge
import com.openbank.engagement.domain.model.gamification.EarnSource
import com.openbank.engagement.domain.model.gamification.GamificationAwardRule
import com.openbank.engagement.domain.model.gamification.Points
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GamificationAwardRuleTest {

    @Test
    fun `award carries the challenge's earn source, points and the frozen rule version`() {
        val challenge = Challenge(
            id = "COMPLETE_BUDGETING_COURSE",
            earnSource = EarnSource.EducationalContentCompletion,
            rewardPoints = Points.of(50),
        )
        val party = UUID.randomUUID()
        val correlationEventId = UUID.randomUUID()
        val occurredAt = Instant.now()

        val award = GamificationAwardRule.award(challenge, party, correlationEventId, occurredAt)

        assertThat(award.partyId).isEqualTo(party)
        assertThat(award.challengeId).isEqualTo(challenge.id)
        assertThat(award.earnSource).isEqualTo(EarnSource.EducationalContentCompletion)
        assertThat(award.points).isEqualTo(Points.of(50))
        assertThat(award.ruleVersion).isEqualTo(GamificationAwardRule.RULE_VERSION)
        assertThat(award.correlationEventId).isEqualTo(correlationEventId)
        assertThat(award.occurredAt).isEqualTo(occurredAt)
    }

    @Test
    fun `the correlation id is the caller-supplied triggering event id, never freshly minted`() {
        val challenge = Challenge(id = "X", earnSource = EarnSource.LoginStreak, rewardPoints = Points.ZERO)
        val correlationEventId = UUID.randomUUID()

        val award = GamificationAwardRule.award(challenge, UUID.randomUUID(), correlationEventId, Instant.now())

        assertThat(award.correlationEventId).isEqualTo(correlationEventId)
    }
}
