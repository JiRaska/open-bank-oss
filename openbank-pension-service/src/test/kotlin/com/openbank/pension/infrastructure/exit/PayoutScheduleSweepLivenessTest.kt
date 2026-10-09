// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.exit

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.pension.application.exit.ExitWorkflowLauncher
import com.openbank.pension.application.exit.PayoutService
import com.openbank.pension.domain.exit.PayoutRequest
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class PayoutScheduleSweepLivenessTest {
    private val registry = SimpleMeterRegistry()
    private val payouts = mockk<PayoutService>()
    private val launcher = mockk<ExitWorkflowLauncher>(relaxed = true)

    private fun scheduler(interval: String = "30m"): PayoutScheduleSweep {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        val metrics = DomainMetrics().apply { registryInstance = instance }
        return PayoutScheduleSweep(payouts, launcher, Clock.systemUTC(), registry, metrics, interval)
    }

    private fun gauge(name: String): Double = registry.find(name)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "pension-payout-schedule-sweep")
        .gauge()!!.value()

    @Test
    fun `registration reports configured cadence and no successful run yet`() {
        scheduler()
        assertThat(gauge(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)).isEqualTo(1800.0)
        assertThat(gauge(WorkflowLivenessMetrics.SUCCESS_RECORDED)).isZero()
    }

    @Test
    fun `a disabled schedule publishes no heartbeat`() {
        listOf("off", "OFF", "disabled", "DISABLED").forEach { interval ->
            scheduler(interval)
            assertThat(registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED).gauge()).isNull()
            assertThat(registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS).gauge()).isNull()
        }
    }

    @Test
    fun `an empty successful sweep records a heartbeat`(): Unit = runBlocking {
        coEvery { payouts.overdue(any()) } returns emptyList()
        val scheduler = scheduler()
        scheduler.sweep()
        assertThat(gauge(WorkflowLivenessMetrics.SUCCESS_RECORDED)).isEqualTo(1.0)
        verify(exactly = 0) { launcher.startPayout(any()) }
    }

    @Test
    fun `a completed workflow restart records success`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val payout = mockk<PayoutRequest>()
        every { payout.id } returns id
        coEvery { payouts.overdue(any()) } returns listOf(payout)
        val scheduler = scheduler()
        scheduler.sweep()
        assertThat(gauge(WorkflowLivenessMetrics.SUCCESS_RECORDED)).isEqualTo(1.0)
        verify(exactly = 1) { launcher.startPayout(id) }
    }

    @Test
    fun `a failed repository read does not record success`(): Unit = runBlocking {
        coEvery { payouts.overdue(any()) } throws IllegalStateException("repository unavailable")
        val scheduler = scheduler()
        assertThat(runCatching { scheduler.sweep() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(gauge(WorkflowLivenessMetrics.SUCCESS_RECORDED)).isZero()
        verify(exactly = 0) { launcher.startPayout(any()) }
    }

    @Test
    fun `a failed workflow restart does not record success`(): Unit = runBlocking {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val payout = mockk<PayoutRequest>()
        every { payout.id } returns id
        coEvery { payouts.overdue(any()) } returns listOf(payout)
        every { launcher.startPayout(id) } throws IllegalStateException("workflow unavailable")
        val scheduler = scheduler()
        assertThat(runCatching { scheduler.sweep() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(gauge(WorkflowLivenessMetrics.SUCCESS_RECORDED)).isZero()
        verify(exactly = 1) { launcher.startPayout(id) }
    }
}
