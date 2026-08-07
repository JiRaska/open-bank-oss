// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.domain

import com.openbank.engagement.domain.model.AdverseState
import com.openbank.engagement.domain.model.EligibilityRule
import com.openbank.engagement.domain.model.EligibilitySnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EligibilityRuleTest {

    private fun snapshot(adverseState: Set<AdverseState>) =
        EligibilitySnapshot(partyId = UUID.randomUUID(), adverseState = adverseState, asOf = Instant.now())

    @Test
    fun `a party with no adverse state is eligible for targeting`() {
        assertThat(EligibilityRule.isEligibleForPromotionalTargeting(snapshot(emptySet()))).isTrue
    }

    @Test
    fun `a fraud hold alone excludes targeting`() {
        assertThat(EligibilityRule.isEligibleForPromotionalTargeting(snapshot(setOf(AdverseState.FRAUD_HOLD))))
            .isFalse
    }

    @Test
    fun `every adverse state individually excludes targeting`() {
        AdverseState.entries.forEach { state ->
            assertThat(EligibilityRule.isEligibleForPromotionalTargeting(snapshot(setOf(state))))
                .`as`("state=%s", state)
                .isFalse
        }
    }
}
