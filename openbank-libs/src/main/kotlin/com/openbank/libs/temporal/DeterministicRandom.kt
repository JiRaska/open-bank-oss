// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.temporal

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Qualifier
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * CDI qualifier for injection points that require a seeded, deterministic
 * random source (e.g. idempotency keys derived from a replay-safe seed).
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
annotation class DeterministicRandomSource

/**
 * Seeded pseudo-random source suitable for deterministic test scenarios
 * and replay-safe ID generation.
 *
 * The seed is read from `openbank.temporal.deterministic-random.seed`
 * (default: `0`). In production leave the default; in tests set a fixed
 * seed to get reproducible sequences.
 */
@ApplicationScoped
class DeterministicRandom(
    @ConfigProperty(name = "openbank.temporal.deterministic-random.seed", defaultValue = "0")
    private val seed: Long = 0L,
) {
    private val random = java.util.Random(seed)

    /** Returns the next pseudo-random [Long] from the seeded sequence. */
    fun nextLong(): Long = random.nextLong()

    /**
     * Returns a pseudo-random [UUID] built from two successive [Long] values.
     * Sequential calls produce different UUIDs; the sequence is fully
     * determined by [seed].
     */
    fun nextUUID(): UUID = UUID(random.nextLong(), random.nextLong())
}
