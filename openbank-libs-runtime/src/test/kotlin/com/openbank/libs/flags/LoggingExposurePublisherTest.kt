// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LoggingExposurePublisherTest {

    @Test
    fun `logging publisher emits without throwing`(): Unit = runBlocking {
        val publisher: ExposurePublisher = LoggingExposurePublisher()
        val eval = FlagEvaluation("f", true, variant = "on", reason = EvaluationReason.TARGETING_MATCH)

        publisher.publish(FlagExposure.of(eval, targetingKey = "k"))
    }
}
