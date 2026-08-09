// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.account.infrastructure.scheduler

import com.openbank.account.application.usecase.SavingsProposalService
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
import java.time.Duration

/**
 * ADR-0237: the expiry sweep must publish a liveness heartbeat, and the heartbeat must move ONLY
 * when a sweep actually succeeded.
 *
 * The failure case is the load-bearing one. [SavingsProposalExpiryScheduler] swallows its own
 * exception on purpose (`runCatching` + `getOrDefault(0)`) so one bad tick cannot kill the schedule
 * — which means a permanently broken sweep is externally indistinguishable from a healthy quiet
 * one: nothing throws, nothing logs at INFO, and "0 proposals expired" is the normal case. A
 * heartbeat recorded outside the success path would assert exactly the thing it exists to disprove.
 */
class SavingsProposalExpiryWorkflowLivenessTest {

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
    fun `registers the gauges at startup and records success after a sweep`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val service = mockk<SavingsProposalService>()
        coEvery { service.expireStale(any()) } returns 3

        val scheduler = SavingsProposalExpiryScheduler(service, metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())

        // Registered but never succeeded. The age gauge is SEEDED AT REGISTRATION (#4208), so a
        // never-run job reads as old as its pod rather than as decades — the "absent-vs-stale"
        // distinction ADR-0160 mechanism 3 relies on now lives in the success flag, not in the age.
        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofMinutes(EXPECTED_INTERVAL_MINUTES).toSeconds().toDouble())

        scheduler.sweep()

        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a swallowed sweep failure leaves the heartbeat stale`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val service = mockk<SavingsProposalService>()
        coEvery { service.expireStale(any()) } throws IllegalStateException("db down")

        val scheduler = SavingsProposalExpiryScheduler(service, metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())

        // The scheduler catches this itself — no exception escapes, which is why the heartbeat is
        // the only externally visible difference between a broken sweep and a healthy one.
        scheduler.sweep()

        // Asserted on the success FLAG, not on the age. Under the boot seed a failed run and a
        // freshly registered one have the same small age, so an age assertion here would pass
        // whatever the sweep did.
        assertThat(successRecordedOf(registry))
            .describedAs("a swallowed failure must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `a sweep that expires nothing still records success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val service = mockk<SavingsProposalService>()
        coEvery { service.expireStale(any()) } returns 0

        val scheduler = SavingsProposalExpiryScheduler(service, metricsOver(registry))
        scheduler.registerLiveness(StartupEvent())

        scheduler.sweep()

        // A quiet run IS a successful run — the heartbeat tracks the schedule, not the workload.
        // The age assertion alone cannot say that any more: the boot seed already puts the age
        // under the tolerance before sweep() is called, so it would hold against a scheduler that
        // recorded nothing. The flag is what distinguishes them.
        assertThat(successRecordedOf(registry))
            .describedAs("a quiet run still records a success")
            .isEqualTo(SUCCEEDED)
        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    private companion object {
        const val WORKFLOW = "account-savings-proposal-expiry"
        const val EXPECTED_INTERVAL_MINUTES = 10L
        const val TOLERANCE_SECONDS = 5.0

        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
        const val SUCCEEDED = 1.0
    }
}
