// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.schedule

import com.openbank.ledger.application.port.`in`.FxRevaluationResult
import com.openbank.ledger.application.port.`in`.FxRevaluationUseCase
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.ledger.infrastructure.schedule.FxRevaluationScheduler
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.persistence.lock.ClusterLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * ADR-0160 mechanism 3 liveness for the ledger's money-path schedulers (#2239).
 *
 * These jobs had NO runtime liveness signal. `SubledgerTieOutBreak` is an `increase()` rule, so a
 * job that never runs produces no increase and the rule stays silent — it alerts on the control
 * finding a break, never on the control being absent. FX revaluation had no metric, no watchdog and
 * no rule at all.
 *
 * The test drives the SCHEDULER, not `DomainMetrics`: asserting the library method works would pass
 * against a scheduler that never calls it, which is the whole defect. It pins both halves — the
 * gauge is registered at startup under the scheduler's own workflow tag, AND the age collapses when
 * the job succeeds. A gauge registered but never recorded reads as maximally stale forever, which
 * is a different bug wearing the same green.
 */
class LedgerWorkflowLivenessTest {

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun ageOf(registry: MeterRegistry, workflow: String): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value()

    /** Runs the block inline, as a pod that wins the advisory lock does. */
    private fun winningLock(): ClusterLock = object : ClusterLock {
        override suspend fun <T> tryRunExclusively(jobName: String, block: suspend () -> T): T? = block()
    }

    private fun scheduler(metrics: DomainMetrics, useCase: FxRevaluationUseCase) =
        FxRevaluationScheduler(useCase, winningLock(), metrics)

    @Test
    fun `fx revaluation registers a liveness gauge at startup and collapses its age on a successful run`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val useCase = object : FxRevaluationUseCase {
                override suspend fun revalue(command: RevalueFxCommand) =
                    FxRevaluationResult(command.date, posted = true, journalId = null, movements = emptyMap())
            }
            val job = scheduler(metricsOver(registry), useCase)

            job.onStart(StartupEvent())

            // Registered, and never-succeeded reads only as old as the registration (seeded at
            // registration time, ADR-0237 — an EPOCH seed would read as decades and any staleness
            // rule would fire for the whole window between a deploy and the first run).
            val neverRan = ageOf(registry, WORKFLOW)
            assertThat(neverRan).describedAs("liveness gauge was not registered at startup").isNotNull()
            assertThat(neverRan!!).isLessThan(TOLERANCE_SECONDS)

            // The expected-interval gauge is the other half of the generic rule's expression;
            // without it the age has no threshold to be compared against.
            assertThat(
                registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                    .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                    .gauge()?.value(),
            ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

            job.revalueDaily()

            assertThat(ageOf(registry, WORKFLOW)!!)
                .describedAs("the job ran but recorded no success")
                .isLessThan(TOLERANCE_SECONDS)
        }

    @Test
    fun `a failed run records no success, so the gauge keeps ageing`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val useCase = object : FxRevaluationUseCase {
            override suspend fun revalue(command: RevalueFxCommand): FxRevaluationResult =
                error("revaluation blew up on ${LocalDate.now()}")
        }
        val job = scheduler(metricsOver(registry), useCase)
        job.onStart(StartupEvent())

        // The scheduler swallows the failure by design (it must never crash) — the point of the
        // assertion is that swallowing it must not look like a success. With the gauge seeded at
        // registration (ADR-0237) "not a success" means the age keeps ageing past the run instead
        // of collapsing to ~0: wait past the tolerance, then a failed run must leave the age at or
        // above it (a success would have reset the clock).
        Thread.sleep(1100)
        job.revalueDaily()

        assertThat(ageOf(registry, WORKFLOW)!!)
            .describedAs("a failed run was recorded as a success")
            .isGreaterThanOrEqualTo(1.0)
    }

    private companion object {
        const val WORKFLOW = "ledger-fx-revaluation"
        const val TOLERANCE_SECONDS = 5.0
    }
}
