// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.warmup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WarmupGateTest {

    private class MutableClock(var now: Instant = Instant.parse("2026-10-03T10:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val cap = Duration.ofSeconds(20)

    @Test
    fun `is DOWN before the warm-up starts and while it runs`() {
        val clock = MutableClock()
        val gate = WarmupGate(true, cap, clock)
        assertThat(gate.isReady()).isFalse()
        gate.start()
        clock.now = clock.now.plusSeconds(5)
        assertThat(gate.isReady()).isFalse()
    }

    @Test
    fun `a gate that is never started is still bounded by the cap`() {
        // StartupEvent missed: the first readiness query starts the clock, so the pod cannot be
        // held out of rotation forever.
        val clock = MutableClock()
        val gate = WarmupGate(true, cap, clock)
        assertThat(gate.isReady()).isFalse()
        clock.now = clock.now.plus(cap).plusSeconds(1)
        assertThat(gate.isReady()).isTrue()
    }

    @Test
    fun `is UP once the warm-up has finished`() {
        val gate = WarmupGate(true, cap, MutableClock())
        gate.start()
        gate.finish()
        assertThat(gate.isReady()).isTrue()
    }

    @Test
    fun `is UP after the cap even though the warm-up never finished, and reports it once`() {
        val clock = MutableClock()
        val reported = mutableListOf<Duration>()
        val gate = WarmupGate(true, cap, clock) { reported += it }
        gate.start()
        clock.now = clock.now.plus(cap).minusMillis(1)
        assertThat(gate.isReady()).isFalse()
        clock.now = clock.now.plusMillis(1)
        assertThat(gate.isReady()).isTrue()
        assertThat(gate.isReady()).isTrue()
        assertThat(reported).containsExactly(cap)
    }

    @Test
    fun `a disabled gate is UP immediately`() {
        assertThat(WarmupGate(false, cap, MutableClock()).isReady()).isTrue()
    }

    @Test
    fun `a step that hangs does not hold readiness past the cap`() {
        val clock = Clock.systemUTC()
        val gate = WarmupGate(true, Duration.ofMillis(200), clock)
        val release = CountDownLatch(1)
        gate.start()
        val t = Thread {
            try {
                WarmupRunner.run(
                    listOf(
                        WarmupStep("hang") {
                            release.await()
                            "released"
                        },
                    ),
                )
            } finally {
                gate.finish()
            }
        }.apply {
            isDaemon = true
            start()
        }
        assertThat(gate.isReady()).isFalse()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!gate.isReady() && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(gate.isReady()).isTrue()
        assertThat(gate.isFinished).isFalse()
        release.countDown()
        t.join(5_000)
        assertThat(gate.isFinished).isTrue()
    }

    @Test
    fun `one failing step does not stop the others`() {
        val ran = mutableListOf<String>()
        val results = WarmupRunner.run(
            listOf(
                WarmupStep("first") {
                    ran += "first"
                    "ok"
                },
                WarmupStep("exception") { throw IllegalStateException("boom") },
                WarmupStep("error") { throw UnsatisfiedLinkError("native") },
                WarmupStep("last") {
                    ran += "last"
                    "ok"
                },
            ),
        )
        assertThat(ran).containsExactly("first", "last")
        assertThat(results.map { it.name to it.succeeded }).containsExactly(
            "first" to true,
            "exception" to false,
            "error" to false,
            "last" to true,
        )
        assertThat(results[1].detail).contains("boom")
    }
}
