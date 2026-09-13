// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.notification.application.port.out.NotificationOutboxRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import io.vertx.core.Vertx
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

/**
 * ADR-0237: the dead-letter janitor must publish a liveness heartbeat, and the heartbeat must move
 * ONLY when a purge actually completed.
 *
 * This job is the reason the rule exists. It used to be a plain (non-`suspend`) method, so it ran
 * on a bare `executor-thread` with no Vert.x context and the reactive `purgeDeadBefore` threw
 * `HR000068` on every single firing — straight into the `catch` that exists so one bad tick cannot
 * kill the schedule. No DEAD row was ever purged, nothing escaped, and a count of 0 is the healthy
 * case too, so the job looked identical to a quiet night for as long as it was broken (#2913).
 * The failure test below is therefore the load-bearing one.
 */
class DeadLetterJanitorWorkflowLivenessTest {

    private val repo = mockk<NotificationOutboxRepository>()

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

    private fun job(metrics: DomainMetrics) = NotificationOutboxDeadLetterJanitorJob().also {
        it.outboxRepo = repo
        it.clock = Clock.fixed(Instant.parse("2026-08-14T02:00:00Z"), ZoneOffset.UTC)
        it.domainMetrics = metrics
    }

    private fun onVertxContext(block: suspend () -> Unit) {
        val vertx = Vertx.vertx()
        val completion = CompletableFuture<Unit>()
        vertx.runOnContext {
            CoroutineScope(Dispatchers.Unconfined).launch {
                runCatching { block() }
                    .onSuccess(completion::complete)
                    .onFailure(completion::completeExceptionally)
            }
        }
        try {
            completion.get()
        } finally {
            vertx.close()
        }
    }

    @Test
    fun `registers the gauges at startup and records success after a purge`() {
        val registry = SimpleMeterRegistry()
        every { repo.purgeDeadBefore(any()) } returns Uni.createFrom().item(5L)

        val janitor = job(metricsOver(registry))
        janitor.registerLiveness(StartupEvent())

        // Registered but not yet succeeded. The age gauge is SEEDED AT REGISTRATION (#4208), so a
        // never-run job reads as old as its pod rather than the ~1.8e9 seconds Instant.EPOCH
        // produced — the value that made WorkflowLivenessStale fire 15 minutes after every deploy.
        assertThat(ageOf(registry))
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(successRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())

        onVertxContext { janitor.purgeDeadLetters() }

        assertThat(successRecordedOf(registry)).isEqualTo(SUCCEEDED)
        assertThat(ageOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `a purge that removes nothing still records success`() {
        val registry = SimpleMeterRegistry()
        every { repo.purgeDeadBefore(any()) } returns Uni.createFrom().item(0L)

        val janitor = job(metricsOver(registry))
        janitor.registerLiveness(StartupEvent())
        onVertxContext { janitor.purgeDeadLetters() }

        // A quiet night IS a successful run. Asserted on the success FLAG, not the age: the boot
        // seed already puts the age under the tolerance before purgeDeadLetters() is called, so an
        // age assertion alone would hold against a janitor that recorded nothing at all.
        assertThat(successRecordedOf(registry))
            .describedAs("a zero-row purge still records a success")
            .isEqualTo(SUCCEEDED)
    }

    @Test
    fun `a swallowed purge failure leaves the heartbeat unrecorded`() {
        val registry = SimpleMeterRegistry()
        every { repo.purgeDeadBefore(any()) } returns
            Uni.createFrom().failure(IllegalStateException("HR000068: no current Vertx context"))

        val janitor = job(metricsOver(registry))
        janitor.registerLiveness(StartupEvent())

        // The janitor catches this itself — no exception escapes, which is why the heartbeat is
        // the only externally visible difference between a broken purge and a healthy one. This is
        // the #2913 shape reproduced exactly.
        onVertxContext { janitor.purgeDeadLetters() }

        assertThat(successRecordedOf(registry))
            .describedAs("a swallowed failure must not record a success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val WORKFLOW = "notification-outbox-dead-letter-janitor"
        const val TOLERANCE_SECONDS = 5.0
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
        const val SUCCEEDED = 1.0
    }
}
