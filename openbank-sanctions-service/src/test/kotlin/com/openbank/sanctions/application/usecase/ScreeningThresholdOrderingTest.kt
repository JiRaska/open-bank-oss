// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.usecase

import com.openbank.libs.fincrime.ScreeningPolicy
import com.openbank.sanctions.domain.model.ScreeningProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The name-match thresholds only mean something in order. With `POTENTIAL_HIT_THRESHOLD` equal to
 * `HIT_THRESHOLD`, [ScreeningProfileEvaluator.statusOf] tests HIT first and POTENTIAL_HIT can never
 * be produced — every fuzzy match becomes either a block or nothing, and no case reaches review.
 * Nothing else fails when that happens: each screen still returns a well-formed status.
 */
class ScreeningThresholdOrderingTest {

    private val hit: Double = ScreeningProfileEvaluator.HIT_THRESHOLD
    private val review: Double = ScreeningProfileEvaluator.POTENTIAL_HIT_THRESHOLD
    private val block: Double = ScreeningPolicy.POTENTIAL_HIT_BLOCK_THRESHOLD

    @Test
    fun `the review band is non-empty - POTENTIAL_HIT sits strictly below HIT`() {
        assertThat(review).isGreaterThan(0.0).isLessThan(hit)
    }

    @Test
    fun `a review-floor match is routed to review, not blocked, by the shared rail policy`() {
        assertThat(review).isLessThanOrEqualTo(block)
    }

    @Test
    fun `the CUSTOMER profile searches down to the review floor so POTENTIAL_HIT is reachable from a name`() {
        val evaluator = ScreeningProfileEvaluator(NoJurisdictions)
        assertThat(evaluator.nameSearchFloor(ScreeningProfile.CUSTOMER)).isEqualTo(review).isLessThan(hit)
    }
}
