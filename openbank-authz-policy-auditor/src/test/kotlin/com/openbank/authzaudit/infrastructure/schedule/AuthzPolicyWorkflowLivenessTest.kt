// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.schedule

import com.openbank.authzaudit.application.port.incoming.RunAuthzPolicyCheckUseCase
import com.openbank.authzaudit.domain.model.RunTrigger
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

class AuthzPolicyWorkflowLivenessTest {

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
    fun `scheduler registers gauge at startup and records success after workflow dispatch`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val runCheck = mockk<RunAuthzPolicyCheckUseCase>()
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns true
        coEvery { runCheck.startDetached(RunTrigger.SCHEDULED) } returns "wf-1"

        val scheduler = AuthzPolicyCheckScheduler().apply {
            this.runCheck = runCheck
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(registry)
        }
        scheduler.registerLiveness()

        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(7).toSeconds().toDouble())

        scheduler.runScheduledCheck()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `failed dispatch records no success`() {
        val registry = SimpleMeterRegistry()
        val runCheck = mockk<RunAuthzPolicyCheckUseCase>()
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns true
        coEvery { runCheck.startDetached(RunTrigger.SCHEDULED) } throws IllegalStateException("temporal down")

        val scheduler = AuthzPolicyCheckScheduler().apply {
            this.runCheck = runCheck
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(registry)
        }
        scheduler.registerLiveness()

        assertThatThrownBy { runBlocking { scheduler.runScheduledCheck() } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(successRecordedOf(registry))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val WORKFLOW = "authz-policy-check"
        const val TOLERANCE_SECONDS = 5.0
        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
