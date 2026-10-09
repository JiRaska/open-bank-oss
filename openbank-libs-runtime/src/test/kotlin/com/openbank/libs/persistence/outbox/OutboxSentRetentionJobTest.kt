// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.stream.Stream

class OutboxSentRetentionJobTest {

    private val now = Instant.parse("2026-10-03T03:17:00Z")
    private val registry = SimpleMeterRegistry()

    private class Target(
        override val retentionLabel: String,
        private val rows: Int,
        private val fail: Boolean = false,
        override val sentRetentionExempt: Boolean = false,
    ) : SentOutboxRetention {
        var seen: Duration? = null
        private var left = rows
        override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int {
            seen = olderThan
            if (fail) error("db down")
            val n = minOf(batch, left)
            left -= n
            return n
        }
    }

    private fun job(
        vararg targets: SentOutboxRetention,
        enabled: Boolean = true,
        batchSize: Int = 2,
        maxBatches: Int = 100,
    ): OutboxSentRetentionJob {
        val regInst = mockk<Instance<MeterRegistry>>()
        every { regInst.isResolvable } returns true
        every { regInst.get() } returns registry
        val targetInst = mockk<Instance<SentOutboxRetention>>()
        every { targetInst.iterator() } answers { targets.toList().toMutableList().iterator() }
        every { targetInst.stream() } answers { Stream.of(*targets) }
        return OutboxSentRetentionJob(
            enabled = enabled,
            sentDays = 7,
            batchSize = batchSize,
            maxBatches = maxBatches,
        ).apply {
            this.targets = targetInst
            metrics = DomainMetrics().apply { registryInstance = regInst }
            clock = Clock.fixed(now, ZoneOffset.UTC)
        }
    }

    private fun successRecorded(): Double? = registry.find(
        WorkflowLivenessMetrics.SUCCESS_RECORDED,
    ).tag("workflow", OutboxSentRetentionJob.WORKFLOW_NAME).gauge()?.value()

    @Test
    fun `purges every target with the configured window and counts what it deleted`(): Unit = runBlocking {
        val a = Target("a", rows = 5)
        val b = Target("b", rows = 0)
        val j = job(a, b)
        j.registerLiveness(StartupEvent())

        val purged = j.purgeAll(now)

        assertThat(purged).containsEntry("a", 5L).containsEntry("b", 0L)
        assertThat(a.seen).isEqualTo(Duration.ofDays(7))
        assertThat(registry.find("openbank.outbox.purged").tag("service", "a").counter()?.count()).isEqualTo(5.0)
        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `one failing outbox does not starve the others and withholds the heartbeat`(): Unit = runBlocking {
        val broken = Target("broken", rows = 3, fail = true)
        val fine = Target("fine", rows = 3)
        val j = job(broken, fine)
        j.registerLiveness(StartupEvent())

        val purged = j.purgeAll(now)

        assertThat(purged).containsOnlyKeys("fine").containsEntry("fine", 3L)
        assertThat(
            registry.find("openbank.outbox.purge.failed").tag("service", "broken").counter()?.count(),
        ).isEqualTo(1.0)
        assertThat(successRecorded())
            .describedAs("a run where a purge threw must not read as a successful quiet night")
            .isEqualTo(0.0)
    }

    @Test
    fun `a full purge cap is observable even when the bounded run succeeds`(): Unit = runBlocking {
        val full = Target("full", rows = 201)
        val quiet = Target("quiet", rows = 0)
        val j = job(full, quiet)
        j.registerLiveness(StartupEvent())

        assertThat(registry.find("openbank.outbox.purge.cap.reached").tag("service", "full").gauge()?.value())
            .isEqualTo(0.0)
        assertThat(j.purgeAll(now)).containsEntry("full", 200L).containsEntry("quiet", 0L)
        assertThat(registry.find("openbank.outbox.purge.cap.reached").tag("service", "full").gauge()?.value())
            .isEqualTo(1.0)
        assertThat(registry.find("openbank.outbox.purge.cap.reached").tag("service", "quiet").gauge()?.value())
            .isEqualTo(0.0)
        assertThat(j.purgeAll(now.plusSeconds(86_400))).containsEntry("full", 1L)
        assertThat(registry.find("openbank.outbox.purge.cap.reached").tag("service", "full").gauge()?.value())
            .isEqualTo(0.0)
        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `invalid batch limits fail service startup before a scheduled purge`() {
        val invalidLimits = listOf(
            "batch-size" to job(Target("sca", rows = 1), batchSize = 0),
            "max-batches" to job(Target("sca", rows = 1), maxBatches = 0),
        )
        for ((name, invalid) in invalidLimits) {
            assertThatThrownBy { invalid.registerLiveness(StartupEvent()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("openbank.outbox.retention.$name must be positive")
        }
    }

    @Test
    fun `a service with no outbox registers no liveness gauge`() {
        job().registerLiveness(StartupEvent())
        assertThat(successRecorded()).isNull()
    }

    @Test
    fun `a live evidence reader is not purged or reported as a retention target`(): Unit = runBlocking {
        val evidence = Target("case", rows = 3, sentRetentionExempt = true)
        val ordinary = Target("sca", rows = 2)
        val j = job(evidence, ordinary)
        j.registerLiveness(StartupEvent())

        assertThat(j.purgeAll(now)).containsOnlyKeys("sca").containsEntry("sca", 2L)
        assertThat(evidence.seen).isNull()
        assertThat(registry.find("openbank.outbox.purge.cap.reached").tag("service", "case").gauge()).isNull()
        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `an exempt-only service has no misleading retention heartbeat`(): Unit = runBlocking {
        val evidence = Target("case", rows = 3, sentRetentionExempt = true)
        val j = job(evidence)
        j.registerLiveness(StartupEvent())

        assertThat(j.purgeAll(now)).isEmpty()
        assertThat(evidence.seen).isNull()
        assertThat(successRecorded()).isNull()
    }

    @Test
    fun `disabled means the scheduled entry point purges nothing`(): Unit = runBlocking {
        val a = Target("a", rows = 5)
        val j = job(a, enabled = false)
        j.registerLiveness(StartupEvent())
        j.purge()
        assertThat(a.seen).isNull()
        assertThat(successRecorded()).isNull()
    }
}

class PanacheOutboxRetentionLabelTest {
    @Test
    fun `the table names the outbox in the retention metrics`() {
        assertThat(PanacheOutboxRetention(OutboxTableShape("sca_outbox")).retentionLabel).isEqualTo("sca")
        assertThat(
            PanacheOutboxRetention(OutboxTableShape("sepa_payment_outbox")).retentionLabel,
        ).isEqualTo("sepa-payment")
    }
}
