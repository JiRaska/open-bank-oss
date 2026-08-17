// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.evals

import com.openbank.fraud.domain.model.FraudVerdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves [FraudReviewEvalRunner] can actually report a FAILURE, not merely that it runs —
 * `openbank-libs/governance/evals/README.md`'s own rationale for `run-evals.py --self-test`
 * ("a runner that has quietly lost the ability to fail takes the build down with it rather than
 * reporting green") applies just as much to a deterministic pack as to the LLM replay gate.
 *
 * The fixture below is a **known-bad ground-truth mismatch**: it reuses the real request from the
 * `velocity-h1-burst-surfaces` scenario (which [FraudRuleEngineTest][com.openbank.fraud.domain
 * .rules.FraudRuleEngineTest] already proves trips `VelocityH1ReviewRule` and returns REVIEW) but
 * *claims* ALLOW. It is asserted through [FraudReviewEvalRunner.evaluate] directly — never added
 * to [FRAUD_REVIEW_SCENARIOS] itself, which would make the real gate permanently, uninformatively
 * red instead of proving the one thing this class exists to prove.
 */
class FraudReviewEvalHarnessSelfTest {

    private val knownGoodFixture = FRAUD_REVIEW_SCENARIOS.first { it.id == "velocity-h1-burst-surfaces" }

    @Test
    fun `runner reports FAIL for a deliberately wrong ground truth`() {
        val knownBadFixture = knownGoodFixture.copy(
            id = "self-test-known-bad-ground-truth",
            description = "Deliberately wrong: claims ALLOW for a request that trips the H1 velocity rule.",
            expectedVerdict = FraudVerdict.ALLOW, // WRONG on purpose — the real engine returns REVIEW.
            expectedReasons = listOf("baseline-allow"),
        )

        val result = FraudReviewEvalRunner.evaluate(knownBadFixture)

        assertThat(result.pass)
            .withFailMessage(
                "harness self-test FAILED: a known-bad ground-truth mismatch (expected ALLOW, engine " +
                    "returns REVIEW) was reported as PASS — the harness has lost the ability to detect a " +
                    "regression and the whole gate is now assurance theatre",
            )
            .isFalse()
        // And the mismatch is exactly the one this fixture was built to surface, not an unrelated one.
        assertThat(result.actualVerdict).isEqualTo(FraudVerdict.REVIEW.name)
        assertThat(result.expectedVerdict).isEqualTo(FraudVerdict.ALLOW.name)
    }

    @Test
    fun `runner reports PASS for the same fixture with correct ground truth (control)`() {
        assertThat(FraudReviewEvalRunner.evaluate(knownGoodFixture).pass)
            .withFailMessage("control fixture unexpectedly failed — the comparison logic itself is broken")
            .isTrue()
    }
}
