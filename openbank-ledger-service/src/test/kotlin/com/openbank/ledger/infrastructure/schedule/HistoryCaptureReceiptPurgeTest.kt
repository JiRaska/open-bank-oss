// SPDX-License-Identifier: Apache-2.0
package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.infrastructure.persistence.repository.JournalHistoryCaptureReceipts
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class HistoryCaptureReceiptPurgeTest {
    private val registry = SimpleMeterRegistry()
    private val receipts = mockk<JournalHistoryCaptureReceipts>()

    @AfterEach
    fun cleanup() {
        unmockkObject(ScheduleSwitch)
        registry.close()
    }

    @Test
    fun `zero work completed purge records success with minute cadence`(): Unit = runBlocking {
        coEvery { receipts.purgeExpired() } returns 0
        val job = job()
        assertThat(success()).isEqualTo(0.0)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, HistoryCaptureReceiptPurge.WORKFLOW_NAME)
                .gauge()!!.value(),
        ).isEqualTo(60.0)
        job.purge()
        assertThat(success()).isEqualTo(1.0)
    }

    @Test
    fun `failed purge does not record success and propagates the failure`(): Unit = runBlocking {
        val failure = IllegalStateException("synthetic purge failure")
        coEvery { receipts.purgeExpired() } throws failure
        val job = job()
        val caught = runCatching { job.purge() }.exceptionOrNull()
        assertThat(caught).isSameAs(failure)
        assertThat(success()).isEqualTo(0.0)
    }

    @Test
    fun `off trigger registers no stale heartbeat`() {
        mockkObject(ScheduleSwitch)
        every { ScheduleSwitch.isOff(HistoryCaptureReceiptPurge.TRIGGER_PROPERTY) } returns true
        job()
        assertThat(success()).isNull()
    }

    @Test
    fun `configured ten minute trigger registers the same expected interval`() {
        job("10m")
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, HistoryCaptureReceiptPurge.WORKFLOW_NAME)
                .gauge()!!.value(),
        ).isEqualTo(600.0)
    }

    private fun job(interval: String = "1m"): HistoryCaptureReceiptPurge {
        val registries = mockk<Instance<MeterRegistry>>()
        every { registries.isResolvable } returns true
        every { registries.get() } returns registry
        return HistoryCaptureReceiptPurge(receipts, DomainMetrics().apply { registryInstance = registries }, interval)
    }

    private fun success(): Double? = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, HistoryCaptureReceiptPurge.WORKFLOW_NAME).gauge()?.value()
}
