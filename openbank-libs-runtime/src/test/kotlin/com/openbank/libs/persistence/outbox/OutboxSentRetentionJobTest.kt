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

    private fun job(vararg targets: SentOutboxRetention, enabled: Boolean = true): OutboxSentRetentionJob {
        val regInst = mockk<Instance<MeterRegistry>>()
        every { regInst.isResolvable } returns true
        every { regInst.get() } returns registry
        val targetInst = mockk<Instance<SentOutboxRetention>>()
        every { targetInst.iterator() } answers { targets.toList().toMutableList().iterator() }
        every { targetInst.stream() } answers { Stream.of(*targets) }
        return OutboxSentRetentionJob().apply {
            this.targets = targetInst
            metrics = DomainMetrics().apply { registryInstance = regInst }
            clock = Clock.fixed(now, ZoneOffset.UTC)
            this.enabled = enabled
            sentDays = 7
            batchSize = 2
            maxBatches = 100
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
    fun `a service with no outbox registers no liveness gauge`() {
        job().registerLiveness(StartupEvent())
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
