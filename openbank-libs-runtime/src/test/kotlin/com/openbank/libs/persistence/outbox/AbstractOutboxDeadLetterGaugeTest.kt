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
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AbstractOutboxDeadLetterGaugeTest {

    private class TestGauge(
        override val service: String,
        metrics: DomainMetrics,
        private val deadLettered: () -> Long,
    ) : AbstractOutboxDeadLetterGauge(metrics) {
        override suspend fun currentDeadLettered(): Long = deadLettered()
        fun register() = registerDeadLetterGauge()
        fun bind(recorder: WorkflowLivenessRecorder) = bindLiveness(recorder)
        suspend fun refresh() = refreshDeadLettered()
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
}
