// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.retention

import com.openbank.copilot.application.port.out.ConversationStore
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-0237: the sweep must publish a liveness heartbeat, and the heartbeat must move ONLY when a
 * sweep actually succeeded. The failure case is the load-bearing one — [ConversationRetentionScheduler]
 * swallows its own exceptions on purpose, so a heartbeat recorded outside the success path would make
 * a permanently broken sweep look identical to a healthy one.
 */
class ConversationRetentionWorkflowLivenessTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-06T03:30:00Z"), ZoneOffset.UTC)

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

    private fun schedulerOver(registry: MeterRegistry, store: ConversationStore, enabled: Boolean = true) =
        ConversationRetentionScheduler(store, clock, enabled, metricsOver(registry))

    @Test
    fun `registers the gauges at startup and records success after a sweep`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val store = mockk<ConversationStore>()
        coEvery { store.deleteExpired(any()) } returns 3L

        val scheduler = schedulerOver(registry, store)
        scheduler.registerLiveness(StartupEvent())

        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        scheduler.sweepExpiredConversations()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a swallowed sweep failure records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val store = mockk<ConversationStore>()
        coEvery { store.deleteExpired(any()) } throws IllegalStateException("db down")

        val scheduler = schedulerOver(registry, store)
        scheduler.registerLiveness(StartupEvent())

        // The scheduler catches this itself — no exception escapes, which is exactly why the
        // heartbeat is the only externally visible difference between a broken and a healthy sweep.
        scheduler.sweepExpiredConversations()

        assertThat(successRecordedOf(registry))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `a disabled sweep does not record success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val store = mockk<ConversationStore>()

        val scheduler = schedulerOver(registry, store, enabled = false)
        scheduler.registerLiveness(StartupEvent())

        scheduler.sweepExpiredConversations()

        assertThat(successRecordedOf(registry))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val WORKFLOW = "copilot-conversation-retention"
        const val TOLERANCE_SECONDS = 5.0
        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
