// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimulationRandomTest {

    @Test
    fun `synthetic party ids have version four and RFC variant across seeds`() {
        repeat(100) { seed ->
            val random = SimulationRandom(seed.toLong())
            repeat(100) {
                val id = random.nextUuid()
                assertThat(id.version()).isEqualTo(4)
                assertThat(id.variant()).isEqualTo(2)
            }
        }
    }

    @Test
    fun `replaying a seed reproduces ids and subsequent random choices`() {
        val first = SimulationRandom(42L)
        val replay = SimulationRandom(42L)
        repeat(100) {
            assertThat(first.nextUuid()).isEqualTo(replay.nextUuid())
            assertThat(first.nextInt(1000)).isEqualTo(replay.nextInt(1000))
        }
    }
}
