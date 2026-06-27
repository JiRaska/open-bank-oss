// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

/**
 * The deterministic substrate for one simulation run, all derived from a single [seed]: the
 * seeded RNG, the virtual clock, the fault injector, and the scheduler. Two runs with the same
 * seed and [FaultProfile] are bit-identical, which is what makes a failing run replayable.
 */
class SimulationContext(val seed: Long, faultProfile: FaultProfile) {
    val random: SimulationRandom = SimulationRandom(seed)
    val clock: VirtualClock = VirtualClock.atEpoch()
    val faults: FaultInjector = FaultInjector(faultProfile, random)
    val scheduler: DeterministicScheduler = DeterministicScheduler(clock)
}
