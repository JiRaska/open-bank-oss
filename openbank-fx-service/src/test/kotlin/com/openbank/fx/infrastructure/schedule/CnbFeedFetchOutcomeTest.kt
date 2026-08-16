// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbIngestionResult
import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * Issue #4743 (split from ADR-0237 point 2): the ČNB feed needs a fetch-OUTCOME contract, not just
 * "the scheduled job completed without throwing". `CnbRateIngestionSchedulerTest` already covers
 * the scheduler-never-crashes contract (ADR-0237 point 1); this file covers the four
 * [com.openbank.fx.domain.feed.FeedFetchOutcome] classifications independently, the
 * `openbank.feed.fetch` counter's labels, and — the #4208 lesson — that the `feed-cnb-fx-fixing`
 * liveness gauge reads a sane "not yet run" state on a cold pod rather than decades.
 *
 * Each case is a DIFFERENT trigger, not a shared mock reused with a different assertion: a 200
 * with zero parsed rows (EMPTY) and a completed job (FETCHED) both complete `ingest()` without
 * throwing, and a naive heartbeat cannot tell them apart — which is the exact gap this issue
 * exists to close (a 404 also completes without throwing, and a 200 with zero parsed rows also
 * completes without throwing).
 */
class CnbFeedFetchOutcomeTest {

    private val useCase: CnbRateIngestionUseCase = mockk()

    private fun metricsOver(registry: MeterRegistry): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun outcomeCount(registry: MeterRegistry, outcome: String): Double = registry
        .find("openbank.feed.fetch")
        .tags("feed", "cnb-fx-fixing", "outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    private fun feedAgeOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, FEED_WORKFLOW)
        .gauge()
        ?.value()

    private fun feedSuccessRecordedOf(registry: MeterRegistry): Double? = registry
        .find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, FEED_WORKFLOW)
        .gauge()
        ?.value()

    private fun schedulerOver(registry: MeterRegistry): CnbRateIngestionScheduler {
        val scheduler = CnbRateIngestionScheduler(useCase, metricsOver(registry))
        scheduler.onStart(StartupEvent())
        return scheduler
    }

    @Test
    fun `a 200 with parsed rows records FETCHED and moves the feed liveness gauge`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { useCase.ingest(IngestCnbFixingCommand(date = null)) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 3, 0, listOf("EUR", "USD", "GBP"))

        schedulerOver(registry).ingestDailyFixing()

        assertThat(outcomeCount(registry, "FETCHED")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "EMPTY")).isEqualTo(0.0)
        assertThat(feedSuccessRecordedOf(registry))
            .describedAs("only FETCHED may record a feed-liveness success")
            .isEqualTo(SUCCEEDED)
        assertThat(feedAgeOf(registry)).isLessThan(TOLERANCE_SECONDS)
    }

    @Test
    fun `an idempotent re-run with only already-stored rows still counts as FETCHED`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        // ingested = 0, skipped = 3: yesterday's rows are already there. Real data exists — this
        // must not read the same as EMPTY, which means the feed had NOTHING for this service.
        coEvery { useCase.ingest(IngestCnbFixingCommand(date = null)) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 0, 3, listOf("EUR", "USD", "GBP"))

        schedulerOver(registry).ingestDailyFixing()

        assertThat(outcomeCount(registry, "FETCHED")).isEqualTo(1.0)
        assertThat(feedSuccessRecordedOf(registry)).isEqualTo(SUCCEEDED)
    }

    @Test
    fun `a 200 with zero rows relevant to the configured currencies records EMPTY, not FETCHED`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        // The parser succeeded (this is what a 200-with-nothing-usable looks like from the
        // use-case's own contract) but nothing matched the configured currency set.
        coEvery { useCase.ingest(IngestCnbFixingCommand(date = null)) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 0, 0, emptyList())

        schedulerOver(registry).ingestDailyFixing()

        assertThat(outcomeCount(registry, "EMPTY")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "FETCHED")).isEqualTo(0.0)
        assertThat(feedSuccessRecordedOf(registry))
            .describedAs("EMPTY is a successful-looking no-op and must NOT record a feed-liveness success")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `a non-2xx from the rest client records HTTP_ERROR, not PARSE_ERROR`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { useCase.ingest(any()) } throws WebApplicationException("feed answered 503", 503)

        schedulerOver(registry).ingestDailyFixing()

        assertThat(outcomeCount(registry, "HTTP_ERROR")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "PARSE_ERROR")).isEqualTo(0.0)
        assertThat(feedSuccessRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `an unreachable feed (transport failure) also records HTTP_ERROR`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        coEvery { useCase.ingest(any()) } throws RuntimeException("connection reset")

        schedulerOver(registry).ingestDailyFixing()

        assertThat(outcomeCount(registry, "HTTP_ERROR")).isEqualTo(1.0)
        assertThat(feedSuccessRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `a 2xx body that fails to parse records PARSE_ERROR, not HTTP_ERROR`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        // CnbFixingParser's own require() shape — this is precisely #2204's "soft 404": a 200
        // status carrying a page the parser rejects.
        coEvery { useCase.ingest(any()) } throws
            IllegalArgumentException("Unparseable ČNB fixing date header: '<html>…'")

        schedulerOver(registry).ingestDailyFixing()

        assertThat(outcomeCount(registry, "PARSE_ERROR")).isEqualTo(1.0)
        assertThat(outcomeCount(registry, "HTTP_ERROR")).isEqualTo(0.0)
        assertThat(feedSuccessRecordedOf(registry)).isEqualTo(NOT_YET_SUCCEEDED)
    }

    @Test
    fun `the feed liveness gauge is seeded at registration, not at Instant EPOCH, on a cold pod`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()

        // Only onStart() ran — ingestDailyFixing() has never fired. This is the #4208 trap: a
        // gauge seeded from Instant.EPOCH reads ~1.8e9 seconds (decades) here, which would fire
        // WorkflowLivenessStale (age > 2x expected interval) fifteen minutes after every deploy,
        // for a feed whose real interval is a day. Asserting recency, never mere non-nullity, is
        // the point (an isNotNull() check passes against 1970-01-01 too).
        schedulerOver(registry)

        assertThat(feedAgeOf(registry))
            .describedAs("a never-yet-fetched feed must read as pod-uptime-old, not as decades old")
            .isNotNull
            .isLessThan(BOOT_SEED_CEILING_SECONDS)
        assertThat(feedSuccessRecordedOf(registry))
            .describedAs("boot-time 'never run' must be its own explicit state, distinct from a real success")
            .isEqualTo(NOT_YET_SUCCEEDED)
        assertThat(
            registry.find(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)
                .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, FEED_WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(Duration.ofDays(1).toSeconds().toDouble())
    }

    @Test
    fun `the scheduler heartbeat still records on every run, unlike the feed liveness gauge`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        // EMPTY: the job completed (ADR-0237 point 1's heartbeat), but the feed delivered nothing
        // (ADR-0237 point 2's freshness signal) — the two gauges must disagree on this run.
        coEvery { useCase.ingest(any()) } returns
            CnbIngestionResult(LocalDate.of(2026, 5, 30), 104, 0, 0, emptyList())

        schedulerOver(registry).ingestDailyFixing()

        val schedulerSuccess = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
            .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, "fx-cnb-ingestion")
            .gauge()?.value()

        assertThat(schedulerSuccess)
            .describedAs("the job ran to completion, so its own heartbeat still records success")
            .isEqualTo(SUCCEEDED)
        assertThat(feedSuccessRecordedOf(registry))
            .describedAs("the feed itself delivered nothing usable, so ITS gauge must not")
            .isEqualTo(NOT_YET_SUCCEEDED)
    }

    private companion object {
        const val FEED_WORKFLOW = "feed-cnb-fx-fixing"
        const val TOLERANCE_SECONDS = 5.0
        val BOOT_SEED_CEILING_SECONDS = Duration.ofHours(1).toSeconds().toDouble()
        const val NOT_YET_SUCCEEDED = 0.0
        const val SUCCEEDED = 1.0
    }
}
