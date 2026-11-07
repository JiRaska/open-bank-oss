// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.temporal.TemporalConfig
import com.openbank.liveness.application.port.incoming.RunLivenessCheckUseCase
import com.openbank.liveness.domain.model.RunTrigger
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
 * Body-level coverage of the daily schedule. The cron WIRING is asserted separately by
 * `LivenessSchedulerCronIT`; what is pinned here is what the body decides -- that a fire is
 * counted before anything can bail out, that a Temporal-disabled pod dispatches nothing, and that
 * the dispatch is detached (`startDetached`), never the inline `run` that would hold a scheduler
 * thread for a run that legitimately takes tens of minutes.
 */
class LivenessCheckSchedulerTest {

    private val runCheck = mockk<RunLivenessCheckUseCase>()
    private val temporalConfig = mockk<TemporalConfig>()

    private fun scheduler() = LivenessCheckScheduler().also {
        it.runCheck = runCheck
        it.temporalConfig = temporalConfig
    }

    @Test
    fun `a fire dispatches a detached SCHEDULED run and counts itself`(): Unit = runBlocking {
        every { temporalConfig.enabled() } returns true
        coEvery { runCheck.startDetached(any()) } returns "liveness-check-scheduled-2026-08-02"
        val scheduler = scheduler()
        assertThat(scheduler.fireCount).isZero()

        scheduler.runScheduledCheck()

        assertThat(scheduler.fireCount).isEqualTo(1)
        coVerify(exactly = 1) { runCheck.startDetached(RunTrigger.SCHEDULED) }
        // The inline path would block a scheduler thread for the whole sweep, making a slow check
        // indistinguishable from a hung one.
        coVerify(exactly = 0) { runCheck.run(any()) }
    }

    @Test
    fun `a Temporal-disabled pod counts the fire but dispatches nothing`(): Unit = runBlocking {
        every { temporalConfig.enabled() } returns false
        val scheduler = scheduler()

        scheduler.runScheduledCheck()

        // The count must move even on the skip path: it is the only evidence the cron is wired at
        // all, and an "empty findings table" otherwise reads as "no stale controls".
        assertThat(scheduler.fireCount).isEqualTo(1)
        coVerify(exactly = 0) { runCheck.startDetached(any()) }
    }

    @Test
    fun `repeated fires accumulate`(): Unit = runBlocking {
        every { temporalConfig.enabled() } returns false
        val scheduler = scheduler()

        repeat(3) { scheduler.runScheduledCheck() }

        assertThat(scheduler.fireCount).isEqualTo(3)
    }

    private fun heartbeatScheduler(registry: MeterRegistry): LivenessCheckScheduler {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return scheduler().also {
            it.metrics = DomainMetrics().apply { registryInstance = instance }
            it.registerHeartbeat()
        }
    }

    private fun successRecorded(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "liveness-sentinel-check")
        .gauge()
        ?.value()

    @Test
    fun `the heartbeat exists from boot and flips only after a dispatched check`(): Unit = runBlocking {
        every { temporalConfig.enabled() } returns true
        coEvery { runCheck.startDetached(any()) } returns "liveness-check-scheduled"
        val registry = SimpleMeterRegistry()
        val scheduler = heartbeatScheduler(registry)
        // Registered but not yet succeeded: the series WatcherHeartbeatAbsent needs is present.
        assertThat(successRecorded(registry)).isEqualTo(0.0)

        scheduler.runScheduledCheck()

        assertThat(successRecorded(registry)).isEqualTo(1.0)
    }

    @Test
    fun `a skipped check on a Temporal-disabled pod does not read as a success`(): Unit = runBlocking {
        every { temporalConfig.enabled() } returns false
        val registry = SimpleMeterRegistry()
        val scheduler = heartbeatScheduler(registry)

        scheduler.runScheduledCheck()

        assertThat(scheduler.fireCount).isEqualTo(1)
        assertThat(successRecorded(registry)).isEqualTo(0.0)
    }
}
