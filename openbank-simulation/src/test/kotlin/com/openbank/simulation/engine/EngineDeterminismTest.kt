// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

import com.openbank.simulation.runner.SimulationRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class EngineDeterminismTest {

    @Test
    fun `the same seed produces a bit-identical run result`() {
        val runner = SimulationRunner(FaultProfile.ADVERSARIAL, stepsPerSeed = 30)
        assertThat(runner.runSeed(42L)).isEqualTo(runner.runSeed(42L))
    }

    @Test
    fun `the virtual clock only moves when advanced`() {
        val clock = VirtualClock.atEpoch()
        val start = clock.instant()
        clock.advance(Duration.ofMinutes(5))
        assertThat(clock.instant()).isEqualTo(start.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `the scheduler runs tasks in virtual-time then insertion order`() {
        val scheduler = DeterministicScheduler(VirtualClock.atEpoch())
        val order = mutableListOf<String>()
        scheduler.schedule(Duration.ofMillis(50)) { order.add("late") }
        scheduler.schedule(Duration.ofMillis(10)) { order.add("early-1") }
        scheduler.schedule(Duration.ofMillis(10)) { order.add("early-2") }
        scheduler.drain()
        assertThat(order).containsExactly("early-1", "early-2", "late")
        assertThat(scheduler.pendingCount()).isZero()
    }
}
