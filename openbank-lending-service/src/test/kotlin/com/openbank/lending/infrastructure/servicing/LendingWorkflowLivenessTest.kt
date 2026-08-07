// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.application.port.`in`.RunProvisioningCycleUseCase
import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.lending.domain.model.ProvisioningRunOutcome
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.function.Supplier

/**
 * ADR-0160 mechanism 3 liveness for the lending service's money-path schedulers (#3345).
 *
 * Mirrors [com.openbank.ledger.schedule.LedgerWorkflowLivenessTest]: drives the schedulers,
 * not DomainMetrics directly, so both the registration AND the recordSuccess() call are pinned.
 */
class LendingWorkflowLivenessTest {

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

    @BeforeEach
    fun stubPanacheSession() {
        mockkStatic(Panache::class)
        every { Panache.withSession(any<Supplier<Uni<Void>>>()) } answers {
            firstArg<Supplier<Uni<Void>>>().get()
        }
    }

    @AfterEach
    fun restorePanache() {
        unmockkStatic(Panache::class)
    }

    @Test
    fun `interest accrual registers a liveness gauge at startup and collapses its age on a successful run`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val accrual = object : AccrueInterestUseCase {
            override fun accrueDueInterest(asOf: LocalDate, limit: Int) =
                Uni.createFrom().item(AccrualOutcome(asOf = asOf, installmentsAccrued = 2))
        }
        val scheduler =
            InterestAccrualScheduler(accrual, batchSize = 500, clock = clock, domainMetrics = metricsOver(registry))

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "lending-interest-accrual")
        assertThat(neverRan).describedAs("liveness gauge was not registered at startup").isNotNull()
        assertThat(neverRan!!).isGreaterThan(FIFTY_YEARS_SECONDS)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "lending-interest-accrual")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        scheduler.runAccrualPass().await().indefinitely()

        assertThat(ageOf(registry, "lending-interest-accrual")!!)
            .describedAs("the job ran but recorded no success")
            .isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `interest accrual records success even when no installments were due (zero-work is a successful run)`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val accrual = object : AccrueInterestUseCase {
            override fun accrueDueInterest(asOf: LocalDate, limit: Int) =
                Uni.createFrom().item(AccrualOutcome(asOf = asOf, installmentsAccrued = 0))
        }
        val scheduler =
            InterestAccrualScheduler(accrual, batchSize = 500, clock = clock, domainMetrics = metricsOver(registry))
        scheduler.onStart(StartupEvent())

        scheduler.runAccrualPass().await().indefinitely()

        assertThat(ageOf(registry, "lending-interest-accrual")!!)
            .describedAs("zero-work run should still record success")
            .isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed accrual pass records no success`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val accrual = object : AccrueInterestUseCase {
            override fun accrueDueInterest(asOf: LocalDate, limit: Int): Uni<AccrualOutcome> =
                Uni.createFrom().failure(IllegalStateException("ledger down"))
        }
        val scheduler =
            InterestAccrualScheduler(accrual, batchSize = 500, clock = clock, domainMetrics = metricsOver(registry))
        scheduler.onStart(StartupEvent())

        runCatching { scheduler.runAccrualPass().await().indefinitely() }

        assertThat(ageOf(registry, "lending-interest-accrual")!!)
            .describedAs("a failed run was recorded as a success")
            .isGreaterThan(FIFTY_YEARS_SECONDS)
    }

    @Test
    fun `provisioning cycle registers a liveness gauge at startup and collapses its age on a successful run`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-06-15T04:00:00Z"), ZoneOffset.UTC)
        val cycleUseCase = object : RunProvisioningCycleUseCase {
            override fun runProvisioningCycle(period: String, asOf: LocalDate, limit: Int) =
                Uni.createFrom().item(ProvisioningRunOutcome(period = period, loansAssessed = 5, journalsPosted = 3))
        }
        val scheduler =
            ProvisioningCycleScheduler(
                cycleUseCase,
                batchSize = 500,
                clock = clock,
                domainMetrics = metricsOver(registry),
            )

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "lending-provisioning-cycle")
        assertThat(neverRan).describedAs("liveness gauge was not registered at startup").isNotNull()
        assertThat(neverRan!!).isGreaterThan(FIFTY_YEARS_SECONDS)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "lending-provisioning-cycle")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofHours(720).toSeconds().toDouble())

        scheduler.runProvisioningPass().await().indefinitely()

        assertThat(ageOf(registry, "lending-provisioning-cycle")!!)
            .describedAs("the job ran but recorded no success")
            .isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed provisioning cycle records no success`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-06-15T04:00:00Z"), ZoneOffset.UTC)
        val cycleUseCase = object : RunProvisioningCycleUseCase {
            override fun runProvisioningCycle(
                period: String,
                asOf: LocalDate,
                limit: Int,
            ): Uni<ProvisioningRunOutcome> = Uni.createFrom().failure(IllegalStateException("db unavailable"))
        }
        val scheduler =
            ProvisioningCycleScheduler(
                cycleUseCase,
                batchSize = 500,
                clock = clock,
                domainMetrics = metricsOver(registry),
            )
        scheduler.onStart(StartupEvent())

        runCatching { scheduler.runProvisioningPass().await().indefinitely() }

        assertThat(ageOf(registry, "lending-provisioning-cycle")!!)
            .describedAs("a failed run was recorded as a success")
            .isGreaterThan(FIFTY_YEARS_SECONDS)
    }

    private companion object {
        const val TOLERANCE_SECONDS = 5.0
        val FIFTY_YEARS_SECONDS = Duration.ofDays(50 * 365).toSeconds().toDouble()
    }
}
