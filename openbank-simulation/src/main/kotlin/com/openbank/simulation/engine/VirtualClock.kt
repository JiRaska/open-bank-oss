// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A [Clock] whose time only moves when the simulation moves it (ADR-0100 Layer 1 — clock
 * injection). Wall-clock is the single largest source of non-determinism in money-path
 * tests; replacing it with a virtual clock that the [DeterministicScheduler] advances makes
 * value-date / timestamp logic reproducible from the seed.
 *
 * Not thread-safe by design: a DST run is single-threaded (ADR-0100 Layer 1 §4).
 */
class VirtualClock(private var current: Instant, private val zone: ZoneId = ZoneId.of("Europe/Prague")) : Clock() {

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = VirtualClock(current, zone)

    override fun instant(): Instant = current

    /** Advance virtual time by [duration]. Never moves backwards. */
    fun advance(duration: Duration) {
        require(!duration.isNegative) { "Virtual time cannot move backwards: $duration" }
        current = current.plus(duration)
    }

    /** Jump the clock forward to [target] if it is in the future; otherwise no-op. */
    fun advanceTo(target: Instant) {
        if (target.isAfter(current)) {
            current = target
        }
    }

    companion object {
        /** A fixed, arbitrary epoch so traces are identical regardless of when they run. */
        val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")

        fun atEpoch(): VirtualClock = VirtualClock(EPOCH)
    }
}
