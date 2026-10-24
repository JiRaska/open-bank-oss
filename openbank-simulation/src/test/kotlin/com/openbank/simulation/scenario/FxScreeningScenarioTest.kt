// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.scenario

import com.openbank.fx.domain.screening.ScreeningDecision
import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.World
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0032 §B: exercises [FxScreeningScenario] directly against the REAL
 * `fx-service` `ScreeningPolicy`, so the harness's binding to the sanctions-screening path is
 * proven by actual assertions — every step screens exactly one conversion, the recorded decision
 * is always one the real policy could render, the disposition always matches the decision
 * (CLEAR executes, BLOCK refuses, REVIEW resolves exactly once and lands on the matching side of
 * its resolution), and the invariant holds after every step of a multi-step run.
 */
class FxScreeningScenarioTest {

    private fun newWorld(seed: Long = 42L): World =
        World(SimulationContext(seed, FaultProfile.NONE), SimulationConfig())

    @Test
    fun `a single step records exactly one screened conversion`() {
        val world = newWorld()
        FxScreeningScenario.step(world)

        assertThat(world.screening.conversions()).hasSize(1)
        assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(world)).isNull()
    }

    @Test
    fun `every decision the policy can render appears across seeds and each disposition matches it`() {
        val decisionsSeen = mutableSetOf<ScreeningDecision>()
        (0L until 60L).forEach { seed ->
            val world = newWorld(seed)
            repeat(10) {
                FxScreeningScenario.step(world)
                assertThat(MoneyPathInvariants.screeningDispositionIntegrity.check(world))
                    .withFailMessage("seed %d violated the disposition invariant", seed)
                    .isNull()
            }
            world.screening.conversions().forEach { id ->
                decisionsSeen += world.screening.record(id)!!.decision
            }
        }

        // The seeded score distribution straddles the 0.85 block threshold and the true-hit /
        // escalation injections guarantee BLOCKs; 60 seeds × 10 steps must render all three.
        assertThat(decisionsSeen).containsExactlyInAnyOrder(
            ScreeningDecision.CLEAR,
            ScreeningDecision.REVIEW,
            ScreeningDecision.BLOCK,
        )
    }
}
