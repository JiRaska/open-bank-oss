// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

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

    /**
     * Asserts RECENCY, not non-nullity: `timestamp` defaulted to `Instant.EPOCH`, and `of` — the
     * documented typical call site — never passed one, so every exposure was stamped 1970-01-01.
     * A non-null assertion passes against that, which is what let it survive.
     */
    @Test
    fun `of stamps the exposure at construction time`() {
        val before = Instant.now()

        val stamped = FlagExposure.of(
            FlagEvaluation("checkout-flow", "treatment", variant = "b", reason = EvaluationReason.SPLIT),
            targetingKey = "party-9",
        ).timestamp

        assertThat(stamped).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1))
    }

    @Test
    fun `of falls back to the stringified value when no variant is present`() {
        val eval = FlagEvaluation("ratio", 0.25, reason = EvaluationReason.STATIC)

        assertThat(FlagExposure.of(eval, targetingKey = null).variant).isEqualTo("0.25")
    }

    @Test
    fun `logging publisher emits without throwing`(): Unit = runBlocking {
        val publisher: ExposurePublisher = LoggingExposurePublisher()
        val eval = FlagEvaluation("f", true, variant = "on", reason = EvaluationReason.TARGETING_MATCH)

        publisher.publish(FlagExposure.of(eval, targetingKey = "k"))
    }
}
