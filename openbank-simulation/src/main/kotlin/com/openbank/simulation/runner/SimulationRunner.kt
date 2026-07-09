// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.runner

import com.openbank.simulation.engine.FaultProfile
import com.openbank.simulation.engine.SimulationContext
import com.openbank.simulation.invariants.Invariant
import com.openbank.simulation.invariants.MoneyPathInvariants
import com.openbank.simulation.scenario.FeeBillingScenario
import com.openbank.simulation.scenario.InterestAccrualScenario
import com.openbank.simulation.scenario.PaymentScenario
import com.openbank.simulation.scenario.SepaSettlementScenario
import com.openbank.simulation.scenario.StatementCloseScenario

/**
 * The seed-driven exhaustion loop (ADR-0100 Layer 2 — runner). For each seed it builds a fresh
 * [World], runs [stepsPerSeed] payment steps, drains the scheduler after each scenario, and
 * checks every [invariant]. The first violation aborts that seed and is reported with the seed +
 * step so it can be replayed deterministically — `runSeed(failingSeed)` reproduces the exact
 * trace.
 *
 * The scheduler is drained after EVERY scenario, not just once at the end of the step. A
 * scenario's own affordability decision (e.g. [PaymentScenario]'s reservation,
 * [FeeBillingScenario]'s room check) reads `World.balances.available()` synchronously, but a
 * posted debit's projection (`AccountBookedChanged`) only lands when the scheduler drains
 * ([com.openbank.simulation.adapters.SimEventBus] always schedules it, even with
 * [FaultProfile.NONE]). `PaymentScenario` releases its own reservation as soon as it posts —
 * same step, before any drain — so a single end-of-step drain left a window where a scenario
 * that runs later in the SAME step sees `available()` as if an already-decided, still-in-flight
 * debit from an earlier scenario this step had never happened. Two independently-affordable
 * decisions (by the stale snapshot each scenario saw) could jointly overdraw the account once
 * both debits actually landed — the interleaved billing-fee + payment negative-balance defect
 * (seed=110 step=4: a 2186.14 payment debit + a 2.89 fee debit against an available balance of
 * only 2188.47). Draining after each scenario closes the window: every scenario's affordability
 * check now always sees a fully-settled balance, the same "settled" semantics the invariant
 * sweep already assumes at the end of the step.
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
            context.scheduler.drain()
            // Issue #267 (ADR-0100 full-service adoption): interleave the SEPA + settlement
            // domain-class binding into the same seeded run so it shares the fault profile and
            // is checked by the same invariant sweep every step.
            SepaSettlementScenario.step(world)
            context.scheduler.drain()
            // ADR-0143 phase 2d/2e: interleave the billing fee-charge (+ seeded reversal) path
            // so MoneyPathInvariants.billingFeeConservation is actually exercised every step,
            // instead of vacuously passing against an always-empty World.billingFees.
            FeeBillingScenario.step(world)
            context.scheduler.drain()
            // Issue #667 (E2E money-path): interleave the interest accrual → withholding →
            // capitalization-posting path so it shares the fault profile and is checked by the
            // same invariant sweep every step.
            InterestAccrualScenario.step(world)
            context.scheduler.drain()
            // ADR-0035/0078 / issue #667: the statement period-close path, checked against the
            // ledger's net movement since the account's last close — reads world.ledger/balances
            // only, posts nothing, so this drain is a no-op today but kept for the same
            // "settled before the invariant sweep" convention as every other scenario above.
            StatementCloseScenario.step(world)
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
