// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.schedule

import com.openbank.flakytest.application.port.incoming.RunFlakyTestCheckUseCase
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.temporal.TemporalConfig
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
import java.time.Duration

class FlakyTestWorkflowLivenessTest {

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

    @Test
    fun `scheduler registers gauge at startup and records success after workflow dispatch`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val runCheck = mockk<RunFlakyTestCheckUseCase>()
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns true
        coEvery { runCheck.startDetached(RunTrigger.SCHEDULED) } returns "wf-1"

        val scheduler = FlakyTestCheckScheduler().apply {
            this.runCheck = runCheck
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(registry)
        }
        scheduler.registerLiveness()

        assertThat(ageOf(registry)).isGreaterThan(FIFTY_YEARS_SECONDS)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(7).toSeconds().toDouble())

        scheduler.runScheduledCheck()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `failed dispatch leaves liveness old`() {
        val registry = SimpleMeterRegistry()
        val runCheck = mockk<RunFlakyTestCheckUseCase>()
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns true
        coEvery { runCheck.startDetached(RunTrigger.SCHEDULED) } throws IllegalStateException("temporal down")

        val scheduler = FlakyTestCheckScheduler().apply {
            this.runCheck = runCheck
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(registry)
        }
        scheduler.registerLiveness()

        assertThatThrownBy { runBlocking { scheduler.runScheduledCheck() } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(ageOf(registry)).isGreaterThan(FIFTY_YEARS_SECONDS)
    }

    private companion object {
        const val WORKFLOW = "flaky-test-check"
        const val TOLERANCE_SECONDS = 5.0
        val FIFTY_YEARS_SECONDS = Duration.ofDays(50 * 365).toSeconds().toDouble()
    }
}
