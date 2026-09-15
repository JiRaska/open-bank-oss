// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.schedule

import com.openbank.finops.application.port.incoming.RunFinOpsAnalysisUseCase
import com.openbank.finops.domain.model.RunTrigger
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.temporal.TemporalConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The Temporal-disabled branch, and the fire counter that exists so a test can tell "the cron is
 * wired" apart from "the work happened".
 *
 * The disabled case must still COUNT as a fire (the schedule did run) while recording no success
 * (nothing was dispatched) — collapsing those two would make an environment with Temporal off
 * indistinguishable from a healthy one, which is the failure family this service's own KDoc warns
 * about.
 */
class FinOpsSchedulerDisabledTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun scheduler(
        registry: MeterRegistry,
        enabled: Boolean,
        useCase: RunFinOpsAnalysisUseCase,
    ): FinOpsAnalysisScheduler {
        val temporal = mockk<TemporalConfig>()
        every { temporal.enabled() } returns enabled
        return FinOpsAnalysisScheduler().apply {
            runAnalysis = useCase
            temporalConfig = temporal
            domainMetrics = metricsOver(registry)
            registerLiveness()
        }
    }

    private fun successRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "finops-analysis")
        .gauge()
        ?.value()

    @Test
    fun `with Temporal disabled the cron fires but dispatches nothing and records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val useCase = mockk<RunFinOpsAnalysisUseCase>()
        val scheduler = scheduler(registry, enabled = false, useCase = useCase)

        scheduler.runScheduledAnalysis()

        assertThat(scheduler.fireCount).isEqualTo(1)
        coVerify(exactly = 0) { useCase.startDetached(any()) }
        assertThat(successRecordedOf(registry))
            .describedAs("a skipped run is not a successful run")
            .isEqualTo(0.0)
    }

    @Test
    fun `the fire counter increments once per invocation`(): Unit = runBlocking {
        val useCase = mockk<RunFinOpsAnalysisUseCase>()
        coEvery { useCase.startDetached(RunTrigger.SCHEDULED) } returns "wf"
        val scheduler = scheduler(SimpleMeterRegistry(), enabled = true, useCase = useCase)

        assertThat(scheduler.fireCount).isZero()
        scheduler.runScheduledAnalysis()
        scheduler.runScheduledAnalysis()

        assertThat(scheduler.fireCount).isEqualTo(2)
        coVerify(exactly = 2) { useCase.startDetached(RunTrigger.SCHEDULED) }
    }

    @Test
    fun `the schedule always triggers as SCHEDULED, never as an operator run`(): Unit = runBlocking {
        val useCase = mockk<RunFinOpsAnalysisUseCase>()
        coEvery { useCase.startDetached(any()) } returns "wf"

        scheduler(SimpleMeterRegistry(), enabled = true, useCase = useCase).runScheduledAnalysis()

        coVerify(exactly = 0) { useCase.startDetached(RunTrigger.OPERATOR_MANUAL) }
        coVerify(exactly = 0) { useCase.startDetached(RunTrigger.ALERT_WEBHOOK) }
        coVerify(exactly = 1) { useCase.startDetached(RunTrigger.SCHEDULED) }
    }
}
