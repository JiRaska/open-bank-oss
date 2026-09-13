// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.schedule

import com.openbank.authzaudit.application.port.incoming.RunAuthzPolicyCheckUseCase
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
 * The Temporal-disabled branch. It must be a genuine skip: no dispatch AND no recorded success —
 * a success recorded here would make `openbank_workflow_last_success_age_seconds` report a sweep
 * that never happened, which is exactly the signal the liveness alert exists to contradict.
 */
class AuthzPolicyCheckSchedulerDisabledTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    @Test
    fun `with temporal disabled the cron fires but nothing is dispatched and no success is recorded`() {
        val registry = SimpleMeterRegistry()
        val runCheck = mockk<RunAuthzPolicyCheckUseCase>()
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns false

        val scheduler = AuthzPolicyCheckScheduler().apply {
            this.runCheck = runCheck
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(registry)
        }
        scheduler.registerLiveness()

        runBlocking { scheduler.runScheduledCheck() }

        // The fire counter is what proves the cron itself is wired, independently of the work.
        assertThat(scheduler.fireCount).isEqualTo(1)
        coVerify(exactly = 0) { runCheck.startDetached(any()) }
        assertThat(
            registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "authz-policy-check")
                .gauge()?.value(),
        ).isEqualTo(0.0)
    }

    @Test
    fun `two skipped fires both increment the counter`() {
        val temporalConfig = mockk<TemporalConfig>()
        every { temporalConfig.enabled() } returns false
        val scheduler = AuthzPolicyCheckScheduler().apply {
            this.runCheck = mockk()
            this.temporalConfig = temporalConfig
            domainMetrics = metricsOver(SimpleMeterRegistry())
        }
        scheduler.registerLiveness()

        runBlocking {
            scheduler.runScheduledCheck()
            scheduler.runScheduledCheck()
        }

        assertThat(scheduler.fireCount).isEqualTo(2)
    }
}
