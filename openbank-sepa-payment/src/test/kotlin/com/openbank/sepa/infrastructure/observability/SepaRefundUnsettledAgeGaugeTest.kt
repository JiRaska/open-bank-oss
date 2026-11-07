// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.sepa.application.port.out.SepaRefundQuery
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

class SepaRefundUnsettledAgeGaugeTest {
    private val repository = mockk<SepaRefundQuery>()
    private val registry = SimpleMeterRegistry()
    private val metrics = mockk<DomainMetrics>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private var now = Instant.parse("2026-01-02T12:00:00Z")
    private val clock = mockk<Clock> { every { instant() } answers { now } }

    private fun gauge(): SepaRefundUnsettledAgeGauge {
        every { metrics.registerWorkflowLiveness(any(), Duration.ofSeconds(30)) } returns liveness
        return SepaRefundUnsettledAgeGauge(repository, registry, clock, metrics).also {
            it.register()
            it.onStart(StartupEvent())
        }
    }

    private fun value(): Double = registry.get(SepaRefundUnsettledAgeGauge.METRIC)
        .tags("rail", "SEPA", "operation", "refund").gauge().value()

    @Test
    fun `before first successful query is unknown and successful empty is zero`(): Unit = runBlocking {
        val gauge = gauge()
        assertThat(value()).isNaN()
        coEvery { repository.oldestUnresolvedReturnAt() } returns null
        gauge.refresh()
        assertThat(value()).isZero()
        verify(exactly = 1) { liveness.recordSuccess() }
        assertThat(registry.meters.single().id.tags.map { it.key })
            .containsExactlyInAnyOrder("rail", "operation")
    }

    @Test
    fun `initial query failure stays unknown and records no false success`() {
        val gauge = gauge()
        coEvery { repository.oldestUnresolvedReturnAt() } throws IllegalStateException("synthetic query failure")
        assertThatThrownBy { runBlocking { gauge.refresh() } }.isInstanceOf(IllegalStateException::class.java)
        assertThat(value()).isNaN()
        verify(exactly = 0) { liveness.recordSuccess() }
    }

    @Test
    fun `query failure keeps original timestamp and age continues with injected clock`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { repository.oldestUnresolvedReturnAt() } returns now.minusSeconds(90)
        gauge.refresh()
        assertThat(value()).isEqualTo(90.0)
        coEvery { repository.oldestUnresolvedReturnAt() } throws IllegalStateException("synthetic query failure")
        assertThatThrownBy { runBlocking { gauge.refresh() } }.isInstanceOf(IllegalStateException::class.java)
        now = now.plusSeconds(60)
        assertThat(value()).isEqualTo(150.0)
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    @Test
    fun `successful empty clears age and later query failure retains known empty`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { repository.oldestUnresolvedReturnAt() } returns now.minusSeconds(90)
        gauge.refresh()
        coEvery { repository.oldestUnresolvedReturnAt() } returns null
        gauge.refresh()
        assertThat(value()).isZero()
        coEvery { repository.oldestUnresolvedReturnAt() } throws IllegalStateException("synthetic query failure")
        assertThatThrownBy { runBlocking { gauge.refresh() } }.isInstanceOf(IllegalStateException::class.java)
        now = now.plusSeconds(60)
        assertThat(value()).isZero()
        verify(exactly = 2) { liveness.recordSuccess() }
    }

    @Test
    fun `future persisted receipt clamps to zero without moving its stored timestamp`(): Unit = runBlocking {
        val gauge = gauge()
        coEvery { repository.oldestUnresolvedReturnAt() } returns now.plusSeconds(60)
        gauge.refresh()
        assertThat(value()).isZero()
        now = now.plusSeconds(90)
        assertThat(value()).isEqualTo(30.0)
    }
}
