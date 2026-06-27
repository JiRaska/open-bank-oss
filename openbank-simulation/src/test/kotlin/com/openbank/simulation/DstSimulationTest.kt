// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.runner.SimulationConfig
import com.openbank.simulation.runner.SimulationRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The end-to-end DST proof (ADR-0100). The harness is only credible if it BOTH holds the
 * invariants on correct code under adversarial faults AND catches a real defect — a harness
 * that only ever passes proves nothing. These four tests establish exactly that, and that a
 * failure is reproducible from its seed.
 */
class DstSimulationTest {

    private val steps = 50
    private val seeds = 300

    @Test
    fun `happy path holds every invariant across many seeds`() {
        val runner = SimulationRunner(FaultProfile.NONE, SimulationConfig(), stepsPerSeed = steps)
        val report = runner.run(seeds)
        assertThat(report.allPassed).withFailMessage(report.summary()).isTrue()
    }

    @Test
    fun `adversarial faults hold every invariant when the projection is correctly dedup-guarded`() {
        // Hostile profile: dropped (re-delivered), duplicated and reordered events, write
        // failures and lock conflicts. Correct, idempotent code must still keep every invariant.
        val runner = SimulationRunner(
            FaultProfile.ADVERSARIAL,
            SimulationConfig(dedupEnabled = true),
            stepsPerSeed = steps,
        )
        val report = runner.run(seeds)
        assertThat(report.allPassed).withFailMessage(report.summary()).isTrue()
    }

    @Test
    fun `harness catches the idempotency gap when the projection drops its dedup guard`() {
        // Same hostile faults, but the ledger to balance projection no longer dedups
        // re-delivered events (a realistic money-path defect). The harness MUST find it.
        val runner = SimulationRunner(
            FaultProfile.ADVERSARIAL,
            SimulationConfig(dedupEnabled = false),
            stepsPerSeed = steps,
        )
        val report = runner.run(seeds)

        assertThat(report.allPassed)
            .withFailMessage("expected the dedup-gap bug to be detected, but all seeds passed")
            .isFalse()
        assertThat(report.results.any { it.violation?.invariant == "ledger-balance-projection-consistency" })
            .withFailMessage("expected a projection-consistency violation; got: ${report.summary()}")
            .isTrue()
    }

    @Test
    fun `a failing seed reproduces the exact same violation deterministically`() {
        val runner = SimulationRunner(
            FaultProfile.ADVERSARIAL,
            SimulationConfig(dedupEnabled = false),
            stepsPerSeed = steps,
        )
        val first = runner.run(seeds).firstViolation
        assertThat(first).withFailMessage("expected at least one failing seed").isNotNull()

        val replayA = runner.runSeed(first!!.seed)
        val replayB = runner.runSeed(first.seed)
        assertThat(replayA).isEqualTo(replayB)
        assertThat(replayA.violation).isEqualTo(first.violation)
        assertThat(replayA.stepsRun).isEqualTo(first.stepsRun)
    }
}
