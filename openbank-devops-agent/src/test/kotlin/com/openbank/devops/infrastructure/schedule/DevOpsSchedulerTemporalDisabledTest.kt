// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.schedule

import com.openbank.devops.application.port.incoming.RunDevOpsAnalysisUseCase
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.temporal.TemporalConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Temporal-disabled branch of the daily sweep.
 *
 * It must count the fire (so a test can still prove the cron is wired) but dispatch nothing AND
 * record no success — recording one would make the liveness gauge assert a sweep that never ran,
 * which is precisely the failure `WorkflowLivenessStale` exists to catch.
 */
class DevOpsSchedulerTemporalDisabledTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    @Test
    fun `a disabled Temporal skips dispatch, counts the fire and records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val runAnalysis = mockk<RunDevOpsAnalysisUseCase>()
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns false

        val scheduler = DevOpsAnalysisScheduler().apply {
            this.runAnalysis = runAnalysis
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(registry)
        }
        scheduler.registerLiveness()

        scheduler.runScheduledAnalysis()

        assertThat(scheduler.fireCount).isEqualTo(1)
        coVerify(exactly = 0) { runAnalysis.startDetached(any()) }
        assertThat(
            registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "devops-analysis")
                .gauge()?.value(),
        ).isEqualTo(0.0)
    }

    @Test
    fun `each cron fire increments the counter that proves the schedule is wired`(): Unit = runBlocking {
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns false
        val scheduler = DevOpsAnalysisScheduler().apply {
            this.runAnalysis = mockk()
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(SimpleMeterRegistry())
        }
        scheduler.registerLiveness()

        repeat(3) { scheduler.runScheduledAnalysis() }

        assertThat(scheduler.fireCount).isEqualTo(3)
    }
}
