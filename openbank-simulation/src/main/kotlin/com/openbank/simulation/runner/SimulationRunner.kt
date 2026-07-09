// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.runner

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.Invariant
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.scenario.InterestAccrualScenario
import com.openbank.simulation.scenario.PaymentScenario
import com.openbank.simulation.scenario.SepaSettlementScenario

/**
 * The seed-driven exhaustion loop (ADR-0100 Layer 2 — runner). For each seed it builds a fresh
 * [World], runs [stepsPerSeed] payment steps, drains the scheduler after each, and checks every
 * [invariant]. The first violation aborts that seed and is reported with the seed + step so it
 * can be replayed deterministically — `runSeed(failingSeed)` reproduces the exact trace.
 */
class SimulationRunner(
    private val faultProfile: FaultProfile,
    private val config: SimulationConfig = SimulationConfig(),
    private val stepsPerSeed: Int = 50,
    private val invariants: List<Invariant> = MoneyPathInvariants.ALL,
) {
    fun runSeed(seed: Long): SeedResult {
        val context = SimulationContext(seed, faultProfile)
        val world = World(context, config)
        for (step in 1..stepsPerSeed) {
            PaymentScenario.step(world)
            // Issue #267 (ADR-0100 full-service adoption): interleave the SEPA + settlement
            // domain-class binding into the same seeded run so it shares the fault profile and
            // is checked by the same invariant sweep every step.
            SepaSettlementScenario.step(world)
            // Issue #667 (E2E money-path): interleave the interest accrual → withholding →
            // capitalization-posting path so it shares the fault profile and is checked by the
            // same invariant sweep every step.
            InterestAccrualScenario.step(world)
            context.scheduler.drain()
            invariants.forEach { invariant ->
                val violation = invariant.check(world)
                if (violation != null) return SeedResult(seed, violation, step)
            }
        }
        return SeedResult(seed, null, stepsPerSeed)
    }

    /** Sweep seeds `[0, seedCount)`. */
    fun run(seedCount: Int): SimulationReport {
        val results = (0L until seedCount).map { runSeed(it) }
        return SimulationReport(seedCount, stepsPerSeed, results)
    }
}
