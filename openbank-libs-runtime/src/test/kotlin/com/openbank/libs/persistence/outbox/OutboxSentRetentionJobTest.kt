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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.stream.Stream
import java.util.logging.Logger as JulLogger

class OutboxSentRetentionJobTest {

    private val now = Instant.parse("2026-10-03T03:17:00Z")
    private val registry = SimpleMeterRegistry()

    // Log capture (same idiom as AuditEventPublisherTest): jboss-logmanager is the test JVM's
    // LogManager here, so the JUL logger named after the class's org.jboss.logging category is the
    // same underlying node the production code logs to. The once-vs-always log branches in
    // purgeAll are security-adjacent telemetry (a silent purge must be distinguishable from a
    // purge that did nothing), so these tests pin the formatted line, not just the metric.
    private val julLogger = JulLogger.getLogger("com.openbank.libs.persistence.outbox.OutboxSentRetentionJob")
    private val captured = ConcurrentLinkedQueue<LogRecord>()
    private var originalLevel: Level? = null
    private var originalUseParentHandlers = true
    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            captured += record
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    @BeforeEach
    fun attachHandler() {
        originalLevel = julLogger.level
        originalUseParentHandlers = julLogger.useParentHandlers
        captured.clear()
        julLogger.level = Level.ALL
        julLogger.useParentHandlers = false
        julLogger.addHandler(handler)
    }

    @AfterEach
    fun detachHandler() {
        julLogger.removeHandler(handler)
        julLogger.level = originalLevel
        julLogger.useParentHandlers = originalUseParentHandlers
    }

    /** Joins captured records; falls back to manual String.format when the backend hands back
     * the raw printf format with parameters attached (same either-way assertion as
     * AuditEventPublisherTest). */
    private fun loggedText(): String = captured.joinToString(" | ") {
        val params = it.parameters
        if (params.isNullOrEmpty()) it.message ?: "" else String.format(it.message, *params)
    }

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
        sentDays: Long = 7,
    ): OutboxSentRetentionJob {
        val regInst = mockk<Instance<MeterRegistry>>()
        every { regInst.isResolvable } returns true
        every { regInst.get() } returns registry
        val targetInst = mockk<Instance<SentOutboxRetention>>()
        every { targetInst.iterator() } answers { targets.toList().toMutableList().iterator() }
        every { targetInst.stream() } answers { Stream.of(*targets) }
        return OutboxSentRetentionJob(
            enabled = enabled,
            sentDays = sentDays,
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
    fun `enabled scheduled entry point honors one day window clock and batch cap`(): Unit = runBlocking {
        val calls = mutableListOf<Triple<Duration, Int, Instant>>()
        val target = object : SentOutboxRetention {
            override val retentionLabel = "bounded"
            override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int {
                calls += Triple(olderThan, batch, now)
                return batch
            }
        }
        val j = job(target, sentDays = 1, batchSize = 3, maxBatches = 2)
        j.registerLiveness(StartupEvent())

        j.purge()

        assertThat(calls).containsExactly(
            Triple(Duration.ofDays(1), 3, now),
            Triple(Duration.ofDays(1), 3, now),
        )
        assertThat(registry.find("openbank.outbox.purged").tag("service", "bounded").counter()?.count())
            .isEqualTo(6.0)
        assertThat(registry.find("openbank.outbox.purge.cap.reached").tag("service", "bounded").gauge()?.value())
            .isEqualTo(1.0)
        assertThat(successRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `suspended cancellation stops subsequent targets and withholds heartbeat`(): Unit = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<Int>()
            val target = object : SentOutboxRetention {
                override val retentionLabel = "cancelled"
                override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int = gate.await()
            }
            val untouched = Target("untouched", rows = 1)
            val j = job(target, untouched)
            j.registerLiveness(StartupEvent())
            val run = async(start = CoroutineStart.UNDISPATCHED) { j.purge() }
            assertThat(run.isCompleted).isFalse()

            gate.completeExceptionally(CancellationException("retention shutdown"))
            val failure = runCatching { run.await() }.exceptionOrNull()

            assertThat(failure).isInstanceOf(CancellationException::class.java).hasMessage("retention shutdown")
            assertThat(untouched.seen).isNull()
            assertThat(successRecorded()).isEqualTo(0.0)
            assertThat(registry.find("openbank.outbox.purge.failed").tag("service", "cancelled").counter()).isNull()
        }
    }

    @Test
    fun `scheduled entry point isolates suspended failure and recovers on its next run`(): Unit = runBlocking {
        supervisorScope {
            val gate = CompletableDeferred<Int>()
            var recovered = false
            val target = object : SentOutboxRetention {
                override val retentionLabel = "recovering"
                override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int =
                    if (recovered) 0 else gate.await()
            }
            val healthy = Target("healthy", rows = 1)
            val j = job(target, healthy)
            j.registerLiveness(StartupEvent())
            val run = async(start = CoroutineStart.UNDISPATCHED) { j.purge() }
            assertThat(run.isCompleted).isFalse()
            gate.completeExceptionally(IllegalStateException("db unavailable"))
            run.await()

            assertThat(healthy.seen).isEqualTo(Duration.ofDays(7))
            assertThat(successRecorded()).isEqualTo(0.0)
            assertThat(registry.find("openbank.outbox.purge.failed").tag("service", "recovering").counter()?.count())
                .isEqualTo(1.0)
            recovered = true
            j.purge()
            assertThat(successRecorded()).isEqualTo(1.0)
            assertThat(registry.find("openbank.outbox.purge.failed").tag("service", "recovering").counter()?.count())
                .isEqualTo(1.0)
        }
    }

    @Test
    fun `invalid batch limits fail service startup before a scheduled purge`() {
        val invalidLimits = listOf(
            "sent-days" to job(Target("sca", rows = 1), sentDays = 0),
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

    @Test
    fun `a purge that deletes rows says so, a quiet purge stays silent`(): Unit = runBlocking {
        val noisy = Target("noisy", rows = 3)
        val quiet = Target("quiet", rows = 0)
        val j = job(noisy, quiet)
        j.registerLiveness(StartupEvent())

        j.purgeAll(now)

        // count > 0 guard: the mutant that logs when nothing was purged (negated or >=
        // boundary) prints a line naming the quiet target and fails here.
        assertThat(loggedText())
            .contains("outbox.retention.purged service=noisy count=3")
            .doesNotContain("service=quiet")
    }

    @Test
    fun `a bounded run at the cap warns that older rows may remain, a run below the cap does not`(): Unit =
        runBlocking {
            val full = Target("full", rows = 201) // batchSize 2 * maxBatches 100 -> cap at 200
            val j = job(full)
            j.registerLiveness(StartupEvent())

            j.purgeAll(now)
            assertThat(loggedText()).contains("outbox.retention.cap_reached service=full")

            captured.clear()
            j.purgeAll(now.plusSeconds(86_400)) // one row remains: below the cap
            // capReached guard: the mutant that warns when the cap was NOT reached fails here.
            assertThat(loggedText()).doesNotContain("outbox.retention.cap_reached")
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
