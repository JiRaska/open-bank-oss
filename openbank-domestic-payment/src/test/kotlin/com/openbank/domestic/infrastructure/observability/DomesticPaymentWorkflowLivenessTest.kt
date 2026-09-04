// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.scheduler.ScreeningRedriveScheduler
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DomesticPaymentWorkflowLivenessTest {

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

    private class StubRepository(
        private val counts: Map<DomesticPaymentStatus, Long> = emptyMap(),
        private val oldest: Map<DomesticPaymentStatus, Instant> = emptyMap(),
        private val redrivable: List<UUID> = emptyList(),
        private val failCounts: Boolean = false,
        private val failFindRedrivable: Boolean = false,
    ) : DomesticPaymentRepository {
        override suspend fun countByStatus(status: DomesticPaymentStatus): Long {
            if (failCounts) error("count failed")
            return counts[status] ?: 0L
        }
        override suspend fun oldestCreatedAt(status: DomesticPaymentStatus): Instant? {
            if (failCounts) error("oldest failed")
            return oldest[status]
        }
        override suspend fun findRedrivable(maxAttempts: Int, minAge: Instant, limit: Int): List<UUID> {
            if (failFindRedrivable) error("find failed")
            return redrivable
        }
        override suspend fun recordRedriveAttempt(paymentId: UUID) = Unit
        override suspend fun save(payment: DomesticPayment, outboxMessage: OutboxMessage) = error("unused")
        override suspend fun saveDelegated(
            payment: DomesticPayment,
            outboxMessage: OutboxMessage,
            boundAt: Instant,
            debitOwnerPartyId: UUID,
        ) = error("unused")
        override suspend fun findById(paymentId: UUID) = error("unused")
        override suspend fun findByIdempotencyKey(idempotencyKey: String) = error("unused")
        override suspend fun list(status: DomesticPaymentStatus?, debtorAccountId: UUID?, limit: Int, offset: Int) =
            error("unused")
        override suspend fun update(payment: DomesticPayment, outboxMessage: OutboxMessage) = error("unused")
        override suspend fun claimSchemeDispatch(paymentId: UUID, dispatchedAt: Instant): Boolean = error("unused")
        override suspend fun clearSchemeDispatch(paymentId: UUID) = error("unused")
    }

    @Test
    fun `stranded gauge registers liveness at startup and collapses after a successful refresh`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC)
        val gauge = DomesticPaymentStrandedGauge(
            StubRepository(
                counts = mapOf(DomesticPaymentStatus.RECEIVED to 7L),
                oldest = mapOf(DomesticPaymentStatus.RECEIVED to Instant.now(clock).minus(Duration.ofDays(42))),
            ),
            registry,
            clock,
            metricsOver(registry),
        )

        gauge.onStart(StartupEvent())

        val neverRan = ageOf(registry, "domestic-payment-stranded-gauge")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!)
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry, "domestic-payment-stranded-gauge")).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "domestic-payment-stranded-gauge")
                .gauge()?.value(),
        ).isEqualTo(30.0)

        gauge.register()
        gauge.refresh()

        assertThat(ageOf(registry, "domestic-payment-stranded-gauge")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed stranded-gauge refresh records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC)
        val gauge = DomesticPaymentStrandedGauge(
            StubRepository(failCounts = true),
            registry,
            clock,
            metricsOver(registry),
        )
        gauge.onStart(StartupEvent())
        gauge.register()

        runCatching { gauge.refresh() }

        assertThat(successRecordedOf(registry, "domestic-payment-stranded-gauge"))
            .describedAs("a failed run must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `screening redrive registers liveness at startup and collapses after a successful sweep`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val scheduler = ScreeningRedriveScheduler().apply {
            paymentRepository = StubRepository(redrivable = emptyList())
            workflowClient = mockk(relaxed = true)
            clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC)
            domainMetrics = metricsOver(registry)
            enabled = true
            temporalTaskQueue = "openbank-domestic-payments"
        }

        scheduler.onStart(StartupEvent())

        val neverRan = ageOf(registry, "domestic-payment-screening-redrive")
        assertThat(neverRan).isNotNull()
        assertThat(neverRan!!)
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry, "domestic-payment-screening-redrive")).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "domestic-payment-screening-redrive")
                .gauge()?.value(),
        ).isEqualTo(Duration.ofMinutes(15).toSeconds().toDouble())

        scheduler.sweep()

        assertThat(ageOf(registry, "domestic-payment-screening-redrive")!!).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a failed screening-redrive sweep records no success`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        val workflowClient = mockk<WorkflowClient>()
        every {
            workflowClient.newWorkflowStub(DomesticPaymentWorkflow::class.java, any<WorkflowOptions>())
        } throws IllegalStateException("temporal down")
        val scheduler = ScreeningRedriveScheduler().apply {
            paymentRepository = StubRepository(redrivable = listOf(UUID.randomUUID()))
            this.workflowClient = workflowClient
            clock = Clock.fixed(Instant.parse("2026-08-02T12:00:00Z"), ZoneOffset.UTC)
            domainMetrics = metricsOver(registry)
            enabled = true
            temporalTaskQueue = "openbank-domestic-payments"
        }
        scheduler.onStart(StartupEvent())

        scheduler.sweep()

        assertThat(successRecordedOf(registry, "domestic-payment-screening-redrive"))
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
