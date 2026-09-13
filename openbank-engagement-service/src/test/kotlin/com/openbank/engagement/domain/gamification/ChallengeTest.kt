// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain.gamification

import com.openbank.engagement.domain.model.gamification.Challenge
import com.openbank.engagement.domain.model.gamification.ChallengeCatalog
import com.openbank.engagement.domain.model.gamification.EarnSource
import com.openbank.engagement.domain.model.gamification.Points
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant

class ChallengeTest {

    @Test
    fun `a challenge with a deadline but no genuine expiry is rejected at construction`() {
        assertThatIllegalArgumentException().isThrownBy {
            Challenge(
                id = "FAKE_URGENCY",
                earnSource = EarnSource.EducationalContentCompletion,
                rewardPoints = Points.of(10),
                deadline = Instant.now().plusSeconds(60),
                genuineExpiry = false,
            )
        }.withMessageContaining("genuineExpiry")
    }

    @Test
    fun `a challenge with a genuine deadline is constructible`() {
        val deadline = Instant.now().plusSeconds(3600)
        val challenge = Challenge(
            id = "GENUINE_DEADLINE",
            earnSource = EarnSource.EducationalContentCompletion,
            rewardPoints = Points.of(10),
            deadline = deadline,
            genuineExpiry = true,
        )
        assertThat(challenge.deadline).isEqualTo(deadline)
    }

    @Test
    fun `a challenge with no deadline needs no genuineExpiry declaration`() {
        val challenge = Challenge(
            id = "NO_DEADLINE",
            earnSource = EarnSource.EducationalContentCompletion,
            rewardPoints = Points.of(10),
        )
        assertThat(challenge.deadline).isNull()
        assertThat(challenge.genuineExpiry).isFalse
    }

    @Test
    fun `a blank challenge id is rejected`() {
        assertThatIllegalArgumentException().isThrownBy {
            Challenge(id = " ", earnSource = EarnSource.EducationalContentCompletion, rewardPoints = Points.ZERO)
        }
    }

    @Test
    fun `the reviewed catalogue only references real ids`() {
        ChallengeCatalog.ALL.forEach { (key, challenge) -> assertThat(challenge.id).isEqualTo(key) }
        assertThat(ChallengeCatalog.exists("COMPLETE_BUDGETING_COURSE")).isTrue
        assertThat(ChallengeCatalog.exists("NOT_A_CHALLENGE")).isFalse
    }
}
