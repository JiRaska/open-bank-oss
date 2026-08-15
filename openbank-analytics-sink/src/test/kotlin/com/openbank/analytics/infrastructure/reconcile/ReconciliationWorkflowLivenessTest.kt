// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure.reconcile

import com.openbank.analytics.application.port.out.IntegrityAnchor
import com.openbank.analytics.application.port.out.ReconciliationSource
import com.openbank.analytics.application.port.out.WarehouseStateReader
import com.openbank.analytics.application.port.out.WormArchive
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ReconciliationWorkflowLivenessTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ageOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    private fun successRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
        .gauge()
        ?.value()

    @Test
    fun `job registers gauge at startup and records success after reconciliation finishes`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val source = mockk<ReconciliationSource>()
        val warehouse = mockk<WarehouseStateReader>()
        val worm = mockk<WormArchive>()
        coEvery { source.currentVersions() } returns emptyMap()
        coEvery { source.rowCountsByType() } returns emptyMap()
        coEvery { warehouse.currentVersions() } returns emptyMap()
        coEvery { warehouse.rowCountsByType() } returns emptyMap()
        coEvery { warehouse.versionsByAggregate() } returns emptyMap()
        coEvery { worm.latest() } returns null
        coEvery { worm.seal(any<IntegrityAnchor>()) } returns Unit

        val job = ReconciliationJob().apply {
            this.source = source
            clock = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC)
            this.warehouse = warehouse
            this.worm = worm
            domainMetrics = metricsOver(registry)
        }
        job.registerLiveness()

        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        job.run("scheduled")

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `failed reconciliation records no success`() {
        val registry = SimpleMeterRegistry()
        val source = mockk<ReconciliationSource>()
        coEvery { source.currentVersions() } throws IllegalStateException("source unavailable")

        val job = ReconciliationJob().apply {
            this.source = source
            clock = Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC)
            warehouse = mockk()
            worm = mockk()
            domainMetrics = metricsOver(registry)
        }
        job.registerLiveness()

        assertThatThrownBy { runBlocking { job.run("scheduled") } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(successRecordedOf(registry))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val WORKFLOW = "analytics-reconciliation"
        const val TOLERANCE_SECONDS = 5.0

        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
