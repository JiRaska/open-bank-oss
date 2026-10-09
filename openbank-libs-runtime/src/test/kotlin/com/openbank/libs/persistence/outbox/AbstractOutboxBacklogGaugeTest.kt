// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AbstractOutboxBacklogGaugeTest {

    private class TestGauge(
        override val service: String,
        private val metrics: DomainMetrics,
        private val backlog: suspend () -> Long,
    ) : AbstractOutboxBacklogGauge(metrics) {
        override suspend fun currentBacklog(): Long = backlog()
        fun register() = registerBacklogGauge()
        suspend fun refresh() = refreshBacklog()
    }

    @Test
    fun `suspended refresh updates on success and retains last value on resumed failure`(): Unit = runBlocking {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog("party", capture(supplier)) } returns Unit
        var result = CompletableDeferred<Long>()
        val gauge = TestGauge("party", metrics) { result.await() }
        gauge.register()
        val success = async(start = CoroutineStart.UNDISPATCHED) { gauge.refresh() }
        assertThat(success.isCompleted).isFalse()
        assertThat(supplier.captured().toLong()).isZero()
        result.complete(23)
        success.await()
        assertThat(supplier.captured().toLong()).isEqualTo(23)

        result = CompletableDeferred()
        val failure = async(start = CoroutineStart.UNDISPATCHED) { gauge.refresh() }
        assertThat(failure.isCompleted).isFalse()
        result.completeExceptionally(java.net.ConnectException("test database unavailable"))
        failure.await()
        assertThat(supplier.captured().toLong()).isEqualTo(23)
        verify(exactly = 1) { metrics.outboxGaugeRefreshFailed("party", "backlog") }
    }

    @Test
    fun `registerBacklogGauge registers exactly one gauge under this gauge's own service tag`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val gauge = TestGauge("party", metrics) { 0L }

        gauge.register()

        // Falsifying assertion: a negated-conditional or dropped-call mutant that never invokes
        // registerOutboxBacklog, or one that fires under a different service tag, fails this.
        verify(exactly = 1) { metrics.registerOutboxBacklog("party", any()) }
    }

    @Test
    fun `the registered gauge supplier reflects the value refreshBacklog last cached`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog("party", capture(supplier)) } returns Unit
        var backing = 0L
        val gauge = TestGauge("party", metrics) { backing }
        gauge.register()

        // Before any refresh, the cache starts at 0 — not the repository's current value.
        assertThat(supplier.captured().toLong()).isEqualTo(0L)

        backing = 42L
        runBlocking { gauge.refresh() }

        // Falsifying assertion: a mutant that drops the AtomicLong::set call, negates the
        // refresh conditional, or replaces refreshBacklog's return with null all leave the
        // supplier reading the stale 0 instead of the freshly queried 42.
        assertThat(supplier.captured().toLong()).isEqualTo(42L)

        backing = 7L
        runBlocking { gauge.refresh() }
        assertThat(supplier.captured().toLong()).isEqualTo(7L)
    }

    @Test
    fun `refreshBacklog actually invokes currentBacklog rather than a no-op`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        var callCount = 0
        val gauge = TestGauge("card-issuance", metrics) {
            callCount++
            5L
        }

        runBlocking { gauge.refresh() }
        runBlocking { gauge.refresh() }

        assertThat(callCount).isEqualTo(2)
    }

    @Test
    fun `a failing refresh does not throw, keeps the last good value and counts the failure`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog("sca", capture(supplier)) } returns Unit
        var fail = false
        val gauge = TestGauge("sca", metrics) {
            if (fail) throw java.net.ConnectException("Connection refused") else 9L
        }
        gauge.register()
        runBlocking { gauge.refresh() }

        fail = true
        // Falsifying: without the catch this throws out of the scheduler tick (sandbox: 192x in 48h).
        runBlocking { gauge.refresh() }

        assertThat(supplier.captured().toLong()).isEqualTo(9L)
        verify(exactly = 1) { metrics.outboxGaugeRefreshFailed("sca", "backlog") }
    }

    @Test
    fun `cancellation is not swallowed`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val gauge = TestGauge("sca", metrics) { throw kotlinx.coroutines.CancellationException("stop") }

        org.assertj.core.api.Assertions.assertThatThrownBy { runBlocking { gauge.refresh() } }
            .isInstanceOf(java.util.concurrent.CancellationException::class.java)
        verify(exactly = 0) { metrics.outboxGaugeRefreshFailed(any(), any()) }
    }
}
