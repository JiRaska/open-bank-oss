// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
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

class AbstractOutboxDeadLetterGaugeTest {

    private class TestGauge(
        override val service: String,
        metrics: DomainMetrics,
        private val deadLettered: suspend () -> Long,
    ) : AbstractOutboxDeadLetterGauge(metrics) {
        override suspend fun currentDeadLettered(): Long = deadLettered()
        fun register() = registerDeadLetterGauge()
        fun bind(recorder: WorkflowLivenessRecorder) = bindLiveness(recorder)
        suspend fun refresh() = refreshDeadLettered()
    }

    @Test
    fun `suspended refresh records success only after resumed repository success`(): Unit = runBlocking {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val recorder = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered("party", capture(supplier)) } returns Unit
        var result = CompletableDeferred<Long>()
        val gauge = TestGauge("party", metrics) { result.await() }
        gauge.register()
        gauge.bind(recorder)
        val success = async(start = CoroutineStart.UNDISPATCHED) { gauge.refresh() }
        assertThat(success.isCompleted).isFalse()
        verify(exactly = 0) { recorder.recordSuccess() }
        result.complete(23)
        success.await()
        assertThat(supplier.captured().toLong()).isEqualTo(23)

        result = CompletableDeferred()
        val failure = async(start = CoroutineStart.UNDISPATCHED) { gauge.refresh() }
        assertThat(failure.isCompleted).isFalse()
        result.completeExceptionally(java.net.ConnectException("test database unavailable"))
        failure.await()
        assertThat(supplier.captured().toLong()).isEqualTo(23)
        verify(exactly = 1) { recorder.recordSuccess() }
        verify(exactly = 1) { metrics.outboxGaugeRefreshFailed("party", "dead_lettered") }
    }

    @Test
    fun `registerDeadLetterGauge registers exactly one gauge under this gauge's own service tag`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val gauge = TestGauge("party", metrics) { 0L }

        gauge.register()

        // Falsifying assertion: a negated-conditional or dropped-call mutant that never invokes
        // registerOutboxDeadLettered, or fires under a different service tag, fails this.
        verify(exactly = 1) { metrics.registerOutboxDeadLettered("party", any()) }
    }

    @Test
    fun `the registered gauge supplier reflects the value refreshDeadLettered last cached`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered("party", capture(supplier)) } returns Unit
        var backing = 0L
        val gauge = TestGauge("party", metrics) { backing }
        gauge.register()

        assertThat(supplier.captured().toLong()).isEqualTo(0L)

        backing = 13L
        runBlocking { gauge.refresh() }

        // Falsifying assertion: a mutant that drops AtomicLong::set, negates the refresh
        // conditional, or replaces refreshDeadLettered's return with null leaves the supplier
        // reading the stale 0 instead of the freshly queried 13.
        assertThat(supplier.captured().toLong()).isEqualTo(13L)
    }

    @Test
    fun `refreshDeadLettered records a liveness success on every tick when bound`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val gauge = TestGauge("card-issuance", metrics) { 3L }
        gauge.register()
        gauge.bind(liveness)

        runBlocking { gauge.refresh() }
        runBlocking { gauge.refresh() }

        // Falsifying assertion: a mutant that removes the
        // WorkflowLivenessRecorder::recordSuccess call fires this 0 times instead of 2.
        verify(exactly = 2) { liveness.recordSuccess() }
    }

    @Test
    fun `refreshDeadLettered does not throw when no liveness recorder was ever bound`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val gauge = TestGauge("standalone", metrics) { 0L }
        gauge.register()

        // No bind() call — the base class must tolerate a never-bound liveness reference.
        runBlocking { gauge.refresh() }
    }

    @Test
    fun `refreshDeadLettered actually invokes currentDeadLettered rather than a no-op`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        var callCount = 0
        val gauge = TestGauge("card-issuance", metrics) {
            callCount++
            1L
        }

        runBlocking { gauge.refresh() }
        runBlocking { gauge.refresh() }

        assertThat(callCount).isEqualTo(2)
    }

    @Test
    fun `a failing refresh does not throw, keeps the last value, counts it and records no liveness success`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered("billing", capture(supplier)) } returns Unit
        val recorder = mockk<WorkflowLivenessRecorder>(relaxed = true)
        var fail = false
        val gauge = TestGauge("billing", metrics) {
            if (fail) throw java.net.ConnectException("Connection refused") else 3L
        }
        gauge.register()
        gauge.bind(recorder)
        runBlocking { gauge.refresh() }

        fail = true
        runBlocking { gauge.refresh() }

        assertThat(supplier.captured().toLong()).isEqualTo(3L)
        verify(exactly = 1) { metrics.outboxGaugeRefreshFailed("billing", "dead_lettered") }
        // Liveness must go stale while the DB is unreachable — only the first tick succeeded.
        verify(exactly = 1) { recorder.recordSuccess() }
    }
}
