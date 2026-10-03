// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Readiness state of the startup warm-up (#11890). Pure state machine, no framework types, so
 * the DOWN -> UP transitions are unit-testable without a container.
 *
 *  - before [start]: not ready (the warm-up has not even begun);
 *  - after [start], before [finish]: not ready, UNTIL [maxDuration] elapses — then ready anyway,
 *    because a hung warm-up step must never hold a pod out of rotation forever;
 *  - after [finish]: ready.
 *
 * A disabled gate is ready from construction.
 */
class WarmupGate(
    private val enabled: Boolean,
    private val maxDuration: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val onCapExceeded: (Duration) -> Unit = {},
) {
    @Volatile private var startedAt: Instant? = null

    @Volatile private var finished = false
    private val capReported = AtomicBoolean(false)

    fun start() {
        if (startedAt == null) startedAt = clock.instant()
    }

    fun finish() {
        finished = true
    }

    val isFinished: Boolean get() = finished

    fun isReady(): Boolean {
        if (!enabled || finished) return true
        // A readiness query that arrives before start() (StartupEvent not yet delivered, or never)
        // starts the clock itself: the cap must bound the DOWN state in every path, or a missed
        // event would hold the pod out of rotation forever.
        val started = startedAt ?: clock.instant().also { start() }
        val elapsed = Duration.between(started, clock.instant())
        if (elapsed >= maxDuration) {
            if (capReported.compareAndSet(false, true)) onCapExceeded(elapsed)
            return true
        }
        return false
    }
}
