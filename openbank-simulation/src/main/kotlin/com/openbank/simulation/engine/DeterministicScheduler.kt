// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.engine

import java.time.Duration

/**
 * A single-threaded, virtual-time event queue (ADR-0100 Layer 1 §4 — no thread-scheduler
 * dependence). Replaces the real async runtime: tasks are ordered strictly by
 * `(dueAt, sequence)`, so for a given seed the delivery order is identical on every run and
 * every JVM. Draining the queue advances the [VirtualClock] to each task's due time.
 *
 * Asynchronous money-path effects (e.g. the ledger→balance event projection) are modelled as
 * tasks scheduled with a virtual delay; reordering/duplication are realised by the
 * [FaultInjector] choosing the delay, never by real wall-clock races.
 */
class DeterministicScheduler(private val clock: VirtualClock) {

    private data class ScheduledTask(val dueAtMillis: Long, val sequence: Long, val action: () -> Unit)

    // Ordered by due time, then by insertion sequence to break ties deterministically.
    private val queue = sortedSetOf<ScheduledTask>(
        compareBy({ it.dueAtMillis }, { it.sequence }),
    )
    private var sequence = 0L
    private var nowMillis = 0L

    /** Schedule [action] to run after [delay] of virtual time (zero delay = "soon"). */
    fun schedule(delay: Duration, action: () -> Unit) {
        require(!delay.isNegative) { "delay cannot be negative" }
        queue.add(ScheduledTask(nowMillis + delay.toMillis(), sequence++, action))
    }

    /** Run every queued task in deterministic order until the queue is empty. */
    fun drain() {
        while (queue.isNotEmpty()) {
            val task = queue.first()
            queue.remove(task)
            if (task.dueAtMillis > nowMillis) {
                clock.advance(Duration.ofMillis(task.dueAtMillis - nowMillis))
                nowMillis = task.dueAtMillis
            }
            task.action()
        }
    }

    fun pendingCount(): Int = queue.size
}
