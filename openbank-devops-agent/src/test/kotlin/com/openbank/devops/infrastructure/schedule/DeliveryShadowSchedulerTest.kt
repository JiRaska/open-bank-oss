// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.infrastructure.schedule

import com.openbank.devops.application.port.incoming.CaptureDeliveryObservationUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DeliveryShadowSchedulerTest {
    private val registry = SimpleMeterRegistry()
    private val capture = mockk<CaptureDeliveryObservationUseCase>()

    private fun scheduler(enabled: Boolean): DeliveryShadowScheduler {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        val metrics = DomainMetrics().apply { registryInstance = instance }
        return DeliveryShadowScheduler(capture, metrics, enabled).also { it.registerLiveness() }
    }

    private fun success() = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "devops-delivery-shadow").gauge()?.value()

    @Test
    fun `disabled sampling registers no liveness task and makes no capture`(): Unit = runBlocking {
        scheduler(false).sample()
        assertThat(success()).isNull()
        coVerify(exactly = 0) { capture.capture() }
    }

    @Test
    fun `completed persistence records liveness success`(): Unit = runBlocking {
        coEvery { capture.capture() } returns Unit
        val scheduler = scheduler(true)
        assertThat(success()).isEqualTo(0.0)
        scheduler.sample()
        assertThat(success()).isEqualTo(1.0)
    }

    @Test
    fun `failed persistence cannot record liveness success`() {
        coEvery { capture.capture() } throws IllegalStateException("storage unavailable")
        val scheduler = scheduler(true)
        assertThrows(IllegalStateException::class.java) { runBlocking { scheduler.sample() } }
        assertThat(success()).isEqualTo(0.0)
    }
}
