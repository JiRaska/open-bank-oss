// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.benchmarks

import com.openbank.libs.persistence.outbox.OutboxDispatch
import com.openbank.libs.persistence.outbox.OutboxDispatchResult
import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxRepositoryV2
import com.openbank.libs.persistence.outbox.OutboxStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-0346 local dispatch-kernel cost per batch, including the runBlocking bridge and checks.
 * Prebuilt independent aggregates; in-memory claim/ack and atomic publisher-call accounting.
 * No serialization, database or broker. Publisher bookkeeping and checks are included in the cost.
 * yield exercises suspension on one coroutine event loop, not network latency or parallel CPUs.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class OutboxDispatchBench {
    private lateinit var sequentialSingle: MemoryRepository
    private lateinit var sequentialBatch: MemoryRepository
    private lateinit var concurrentSingle: MemoryRepositoryV2
    private lateinit var concurrentBatch: MemoryRepositoryV2

    @Setup(Level.Trial)
    fun setup() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val rows = List(OutboxDispatch.DEFAULT_BATCH_SIZE) { index ->
            val id = UUID(0L, index.toLong())
            OutboxEntry(id, id, "BenchmarkEvent", "{}", OutboxStatus.DISPATCHING, 0, now, now, null, null)
        }
        sequentialSingle = MemoryRepository(rows.take(1))
        sequentialBatch = MemoryRepository(rows)
        concurrentSingle = MemoryRepositoryV2(rows.take(1))
        concurrentBatch = MemoryRepositoryV2(rows)
        // Reject an empty or partially acknowledged fixture before any measurement.
        sequentialReadySingle()
        sequentialReadyBatch()
        sequentialSuspendingSingle()
        sequentialSuspendingBatch()
        concurrentReadySingle()
        concurrentReadyBatch()
        concurrentSuspendingSingle()
        concurrentSuspendingBatch()
    }

    @Benchmark
    fun sequentialReadySingle(): OutboxDispatchResult = dispatch(sequentialSingle, false)

    @Benchmark
    fun sequentialReadyBatch(): OutboxDispatchResult = dispatch(sequentialBatch, false)

    @Benchmark
    fun sequentialSuspendingSingle(): OutboxDispatchResult = dispatch(sequentialSingle, true)

    @Benchmark
    fun sequentialSuspendingBatch(): OutboxDispatchResult = dispatch(sequentialBatch, true)

    @Benchmark
    fun concurrentReadySingle(): OutboxDispatchResult = dispatch(concurrentSingle, false)

    @Benchmark
    fun concurrentReadyBatch(): OutboxDispatchResult = dispatch(concurrentBatch, false)

    @Benchmark
    fun concurrentSuspendingSingle(): OutboxDispatchResult = dispatch(concurrentSingle, true)

    @Benchmark
    fun concurrentSuspendingBatch(): OutboxDispatchResult = dispatch(concurrentBatch, true)

    private fun dispatch(repository: MemoryRepository, suspending: Boolean): OutboxDispatchResult = runBlocking {
        repository.beginBatch()
        val batchSize = repository.batchSize
        val result = OutboxDispatch.dispatchOnce(repository, batchSize) { entry ->
            if (suspending) yield()
            repository.recordPublisherCall(entry)
        }
        check(result.claimed == batchSize && result.outcomes.size == batchSize && !result.abandoned)
        check(repository.acknowledged == batchSize)
        repository.verifyPublisherCalls()
        result
    }

    private open class MemoryRepository(private val rows: List<OutboxEntry>) : OutboxRepository {
        var acknowledged = 0
        val batchSize: Int = rows.size
        private val publisherCalls = AtomicInteger()
        private val publisherEventIds = AtomicLong()
        private val expectedEventIds = rows.sumOf { it.eventId.leastSignificantBits }

        fun beginBatch() {
            acknowledged = 0
            publisherCalls.set(0)
            publisherEventIds.set(0L)
        }

        fun recordPublisherCall(entry: OutboxEntry) {
            publisherCalls.incrementAndGet()
            publisherEventIds.addAndGet(entry.eventId.leastSignificantBits)
        }

        fun verifyPublisherCalls() {
            check(publisherCalls.get() == batchSize && publisherEventIds.get() == expectedEventIds)
        }

        override suspend fun listProcessable(limit: Int): List<OutboxEntry> {
            check(limit == rows.size)
            return rows
        }

        override suspend fun markSent(eventId: UUID, sentAt: Instant) {
            acknowledged++
        }

        override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
            error("Successful fixture must not mark failures")
    }

    private class MemoryRepositoryV2(rows: List<OutboxEntry>) :
        MemoryRepository(rows),
        OutboxRepositoryV2 {
        override suspend fun markSentBatch(eventIds: Collection<UUID>, sentAt: Instant) {
            acknowledged += eventIds.size
        }

        override suspend fun oldestProcessableAge(now: Instant): Duration? = error("Not part of dispatchOnce")

        override suspend fun purgeDead(olderThan: Duration, batch: Int, now: Instant): Int =
            error("Not part of dispatchOnce")

        override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int =
            error("Not part of dispatchOnce")
    }
}
