// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FlagExposureTest {

    @Test
    fun `of derives variant and reason from the evaluation`() {
        val eval = FlagEvaluation("checkout-flow", "treatment", variant = "b", reason = EvaluationReason.SPLIT)

        val exposure = FlagExposure.of(eval, targetingKey = "party-9", traceId = "trace-1")

        assertThat(exposure.flagKey).isEqualTo("checkout-flow")
        assertThat(exposure.variant).isEqualTo("b")
        assertThat(exposure.targetingKey).isEqualTo("party-9")
        assertThat(exposure.reason).isEqualTo(EvaluationReason.SPLIT)
        assertThat(exposure.traceId).isEqualTo("trace-1")
        assertThat(exposure.exposureId).isNotNull()
    }

    @Test
    fun `of falls back to the stringified value when no variant is present`() {
        val eval = FlagEvaluation("ratio", 0.25, reason = EvaluationReason.STATIC)

        assertThat(FlagExposure.of(eval, targetingKey = null).variant).isEqualTo("0.25")
    }
}
