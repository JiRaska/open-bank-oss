// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

import java.util.Random
import java.util.UUID

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
     * A UUID derived from the seeded stream. NOT a v4 random UUID (that would call
     * SecureRandom); reproducible from the seed, which is the whole point.
     *
     * The version and variant bits are forced to those of a real v4 UUID. Raw random bits land on
     * version 3 one time in sixteen, and domain invariants (a PARTY grantee id may not be a
     * name-based UUID, ADR-0383 D3) correctly reject that shape, so unconstrained ids made whole
     * seeded runs fail with IllegalArgumentException.
     */
    fun nextUuid(): UUID {
        val msb = (random.nextLong() and VERSION_MASK.inv()) or VERSION_4
        val lsb = (random.nextLong() and VARIANT_MASK.inv()) or VARIANT_RFC4122
        return UUID(msb, lsb)
    }

    private companion object {
        const val VERSION_MASK = 0xF000L
        const val VERSION_4 = 0x4000L
        const val VARIANT_MASK = -0x4000000000000000L // top two bits
        const val VARIANT_RFC4122 = Long.MIN_VALUE // 10xx...
    }
}
