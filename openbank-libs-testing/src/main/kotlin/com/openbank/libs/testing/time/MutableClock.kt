// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * A settable, thread-safe [Clock] for deterministic tests (issue #467, ADR-0100 Layer 1).
 * Every service already injects `Clock` via its own near-identical `ClockProducer`
 * (`@Produces @Dependent fun clock(): Clock = Clock.systemUTC()`, duplicated 38× fleet-wide)
 * so domain/application code never calls `Instant.now()` directly — but nothing let a
 * `@QuarkusTest` actually move that injected clock. Plain unit tests already pass
 * `Clock.fixed(...)` straight into a hand-constructed object; this is for the `@QuarkusTest`
 * case, where the clock is CDI-injected and the test needs to advance it mid-test (e.g. to
 * cross a day boundary, or assert a scheduled job fires after time passes).
 *
 * Not itself CDI-aware — pair with [DeterministicClockAlternative] to actually override the
 * injected bean in a `@QuarkusTest`.
 */
class MutableClock(
    initial: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {

    private val current = AtomicReference(initial)

    override fun instant(): Instant = current.get()

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current.get(), zone)

    /** Jump to an absolute instant. */
    fun set(instant: Instant) {
        current.set(instant)
    }

    /** Advance by a duration (e.g. `advance(Duration.ofDays(1))` to cross a day boundary). */
    fun advance(amount: java.time.Duration) {
        current.updateAndGet { it.plus(amount) }
    }
}
