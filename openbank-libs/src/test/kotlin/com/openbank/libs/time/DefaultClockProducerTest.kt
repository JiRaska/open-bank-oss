// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

class DefaultClockProducerTest {

    @Test
    fun `produces a UTC system clock`() {
        val clock = DefaultClockProducer().clock()

        assertThat(clock).isNotNull()
        assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
        assertThat(clock).isEqualTo(Clock.systemUTC())
    }
}
