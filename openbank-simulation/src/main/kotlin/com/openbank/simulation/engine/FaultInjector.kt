// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

/**
 * Probabilities for each adversarial fault the harness can inject (ADR-0100 Layer 1 §2 —
 * I/O virtualisation seam). Rates are independent Bernoulli trials per operation, all driven
 * by the run's single [SimulationRandom], so a seed fully determines which faults fire and
 * when. A profile of all-zero rates is the "happy path" baseline.
 */
data class FaultProfile(
    /** A repository write fails transiently before committing (caller must compensate). */
    val writeFailureRate: Double = 0.0,
    /** A write commits but then a post-commit conflict forces the saga to compensate. */
    val lockConflictRate: Double = 0.0,
    /** A published event is delivered more than once (at-least-once messaging). */
    val duplicateEventRate: Double = 0.0,
    /** A published event's first delivery is deferred and retried (outbox redelivery). */
    val dropEventRate: Double = 0.0,
    /** A pending event takes a longer delay and so may be overtaken by a later one. */
    val reorderEventRate: Double = 0.0,
) {
    companion object {
        val NONE = FaultProfile()

        // Every rate below corresponds to a fault the scenario actually exercises — the
        // profile does not advertise faults the harness never fires.
        /** A deliberately hostile profile used to stress the invariants. */
        val ADVERSARIAL = FaultProfile(
            writeFailureRate = 0.10,
            lockConflictRate = 0.08,
            duplicateEventRate = 0.20,
            dropEventRate = 0.05,
            reorderEventRate = 0.15,
        )
    }
}

/** Raised by a simulated adapter when an injected write fault fires. */
class SimulatedWriteFailure(message: String) : RuntimeException(message)

/**
 * Decides — deterministically, from the seeded RNG — whether each fault fires. Holds no
 * mutable state of its own beyond the shared [random], so the decision stream is a pure
 * function of the seed and the call order.
 */
class FaultInjector(private val profile: FaultProfile, private val random: SimulationRandom) {
    fun shouldFailWrite(): Boolean = random.chance(profile.writeFailureRate)

    fun shouldConflict(): Boolean = random.chance(profile.lockConflictRate)

    fun shouldDuplicateEvent(): Boolean = random.chance(profile.duplicateEventRate)

    fun shouldDropEvent(): Boolean = random.chance(profile.dropEventRate)

    fun shouldReorderEvent(): Boolean = random.chance(profile.reorderEventRate)
}
