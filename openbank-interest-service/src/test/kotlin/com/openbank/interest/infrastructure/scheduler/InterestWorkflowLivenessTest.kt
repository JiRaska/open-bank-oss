// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.`in`.AccrueInterestUseCase
import com.openbank.interest.application.port.`in`.CapitalizeInterestUseCase
import com.openbank.interest.domain.model.AccrualRequest
import com.openbank.interest.domain.model.InterestAccrual
import com.openbank.interest.domain.model.InterestCapitalization
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class InterestWorkflowLivenessTest {

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

    private fun successRecordedOf(registry: MeterRegistry, workflow: String): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value()

    @Test
    fun `interest accrual registers liveness at startup and collapses after a successful run`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val useCase = object : AccrueInterestUseCase {
            override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
            override fun accrueAll(date: LocalDate): Uni<Int> = Uni.createFrom().item(2)
        }
        val scheduler = InterestAccrualScheduler(useCase, clock, metricsOver(registry))

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "interest-accrual")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!)
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry, "interest-accrual")).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "interest-accrual")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        scheduler.runDailyAccrual().await().indefinitely()

        assertThat(ageOf(registry, "interest-accrual")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed interest accrual run records no success`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val useCase = object : AccrueInterestUseCase {
            override fun accrue(request: AccrualRequest): Uni<InterestAccrual> = error("unused")
            override fun accrueAll(date: LocalDate): Uni<Int> =
                Uni.createFrom().failure(IllegalStateException("db down"))
        }
        val scheduler = InterestAccrualScheduler(useCase, clock, metricsOver(registry))
        scheduler.onStart(StartupEvent())

        scheduler.runDailyAccrual().await().indefinitely()

        assertThat(successRecordedOf(registry, "interest-accrual"))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `interest capitalization registers liveness at startup and collapses after a successful run`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val useCase = object : CapitalizeInterestUseCase {
            override fun capitalize(
                accountId: UUID,
                productId: String,
                toDate: LocalDate,
            ): Uni<InterestCapitalization> = error("unused")
            override fun capitalizeAll(toDate: LocalDate): Uni<Int> = Uni.createFrom().item(3)
        }
        val scheduler = InterestCapitalizationScheduler(useCase, clock, metricsOver(registry))

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "interest-capitalization")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!)
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry, "interest-capitalization")).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "interest-capitalization")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofHours(720).toSeconds().toDouble())

        scheduler.runMonthlyCapitalization().await().indefinitely()

        assertThat(ageOf(registry, "interest-capitalization")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed interest capitalization run records no success`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-07-01T04:00:00Z"), ZoneOffset.UTC)
        val useCase = object : CapitalizeInterestUseCase {
            override fun capitalize(
                accountId: UUID,
                productId: String,
                toDate: LocalDate,
            ): Uni<InterestCapitalization> = error("unused")
            override fun capitalizeAll(toDate: LocalDate): Uni<Int> =
                Uni.createFrom().failure(IllegalStateException("ledger down"))
        }
        val scheduler = InterestCapitalizationScheduler(useCase, clock, metricsOver(registry))
        scheduler.onStart(StartupEvent())

        scheduler.runMonthlyCapitalization().await().indefinitely()

        assertThat(successRecordedOf(registry, "interest-capitalization"))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val TOLERANCE_SECONDS = 5.0

        // A workflow registered moments ago is seconds old. This ceiling sits far below the
        // tightest real threshold in the fleet (2x an hourly interval) and astronomically below
        // the ~1.8e9 the EPOCH seed produced, so it fails loudly if the seed ever regresses.
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
    }
}
