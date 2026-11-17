// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

import java.util.Random
import java.util.UUID

private const val UUID_VERSION_MASK = 0xF000L
private const val UUID_VERSION_FOUR = 0x4000L

/**
 * The single seeded source of randomness for a simulation run (ADR-0100 Layer 1 §3).
 *
 * Every non-deterministic choice — which account pays, how much, whether a fault fires,
 * generated ids — flows through one [SimulationRandom] so that a `seed` fully determines the
 * run. Replaying the seed reproduces the exact trace. Backed by [java.util.Random], whose
 * sequence is specified and stable across JVMs, so a failing seed reproduces everywhere.
 *
 * Deliberately NOT [java.security.SecureRandom]: production uses SecureRandom; the DST bean
 * is the deterministic counterpart the ADR calls for.
 */
class SimulationRandom(val seed: Long) {

    private val random = Random(seed)

    fun nextLong(): Long = random.nextLong()

    /** Uniform int in `[0, bound)`. */
    fun nextInt(bound: Int): Int = random.nextInt(bound)

    /** Inclusive long in `[min, max]`. */
    fun nextLong(min: Long, max: Long): Long {
        require(max >= min) { "max < min" }
        val span = max - min + 1
        return min + (Math.floorMod(random.nextLong(), span))
    }

    /** A Bernoulli trial: true with probability [p] (clamped to [0,1]). */
    fun chance(p: Double): Boolean = random.nextDouble() < p.coerceIn(0.0, 1.0)

    /** Pick one element uniformly; the list must be non-empty. */
    fun <T> pick(items: List<T>): T {
        require(items.isNotEmpty()) { "cannot pick from an empty list" }
        return items[random.nextInt(items.size)]
    }

    /**
     * A version-4, RFC-variant UUID derived from the seeded stream. Setting the UUID shape
     * keeps synthetic party ids out of namespaces reserved for name-based client ids.
     * This uses no SecureRandom: replaying the seed reproduces the same ids.
     */
    fun nextUuid(): UUID = UUID(
        (random.nextLong() and UUID_VERSION_MASK.inv()) or UUID_VERSION_FOUR,
        (random.nextLong() and Long.MAX_VALUE.ushr(1)) or Long.MIN_VALUE,
    )
}
