// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The external-feed fetch-outcome contract (ADR-0237 point 2, issue #4743).
 *
 * These tests are about the three properties the contract exists to guarantee, each of which a
 * previous incident in this repo violated:
 *
 *  1. **A no-op does not read as a success.** `FeedFetchOutcome.EMPTY` must leave the feed's
 *     freshness signal exactly where a failure would. `PushResult.skipped()` carried
 *     `success = true`, so pushes that never left the process were counted as delivered (#4348);
 *     the feed version of that is "fetched on schedule, produced nothing, every time" with no error
 *     raised anywhere.
 *  2. **A cold pod does not read as broken.** The age gauge is seeded at registration, so t=0 is
 *     ~0 seconds, not the ~1.8e9 that fired `WorkflowLivenessStale` 15 minutes after every deploy
 *     (#4208). A boot reading is a fourth state beside healthy/degraded/absent and is pinned here
 *     rather than inferred.
 *  3. **The producer and the consumer name the same series.** Mechanism 3's original defect was a
 *     producer and a consumer each spelling a metric name themselves; they disagreed for months and
 *     both sides' tests passed (#2187). The counter makes that trap worse, because Micrometer
 *     appends `_total` to a counter and nothing in the meter name says so.
 *
 * Note what the assertions discriminate BY. "Did the freshness advance" is asserted on
 * `openbank.workflow.success.recorded` (0/1), never on the age gauge's magnitude — an age-threshold
 * assertion would be measuring the seed, and would keep passing, vacuously, the moment the seed
 * changed.
 */
class FeedFetchMetricsTest {

    private fun withRegistry(reg: MeterRegistry): DomainMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        every { inst.isResolvable } returns true
        every { inst.get() } returns reg
        return DomainMetrics().apply { registryInstance = inst }
    }

    private fun MeterRegistry.fetchCount(outcome: FeedFetchOutcome): Double = find(FeedFetchMetrics.FETCH_TOTAL)
        .tag(FeedFetchMetrics.FEED_TAG, FEED)
        .tag(FeedFetchMetrics.OUTCOME_TAG, outcome.name.lowercase())
        .counter()!!
        .count()

    private fun MeterRegistry.freshnessRecorded(): Double = find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, FeedFetchMetrics.freshnessWorkflow(FEED))
        .gauge()!!
        .value()

    private fun MeterRegistry.freshnessAgeSeconds(): Double = find(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, FeedFetchMetrics.freshnessWorkflow(FEED))
        .gauge()!!
        .value()

    // ── 1. the outcome contract ─────────────────────────────────────────────────

    @Test
    fun `every outcome is registered at zero, so an absent series means uninstrumented not never`() {
        val reg = SimpleMeterRegistry()

        withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        assertThat(FeedFetchOutcome.entries.map { reg.fetchCount(it) })
            .describedAs(
                "a counter created lazily on first increment makes 'this never happened' and 'this " +
                    "was never instrumented' the same empty vector — a triage query cannot tell a " +
                    "feed that has never 404'd from one nobody wired up",
            )
            .containsOnly(0.0)
    }

    @Test
    fun `a FETCHED outcome advances the feed freshness`() {
        val reg = SimpleMeterRegistry()
        val feed = withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        feed.record(FeedFetchOutcome.FETCHED)

        assertThat(reg.fetchCount(FeedFetchOutcome.FETCHED)).isEqualTo(1.0)
        assertThat(reg.freshnessRecorded()).isEqualTo(1.0)
    }

    @Test
    fun `an EMPTY outcome is counted but must NOT advance the feed freshness`() {
        val reg = SimpleMeterRegistry()
        val feed = withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        feed.record(FeedFetchOutcome.EMPTY)

        assertThat(reg.fetchCount(FeedFetchOutcome.EMPTY))
            .describedAs("the no-op outcome is observable in its own right, not silence")
            .isEqualTo(1.0)
        assertThat(reg.freshnessRecorded())
            .describedAs(
                "THE test of this contract: a feed that fetched on schedule and produced nothing " +
                    "usable must look exactly as stale as one that 404'd. If EMPTY advanced " +
                    "freshness, a feed delivering nothing forever would be indistinguishable from a " +
                    "healthy one and no error-rate alert could ever see it (#4348's shape).",
            )
            .isEqualTo(0.0)
    }

    @Test
    fun `no failure outcome advances the feed freshness`() {
        FeedFetchOutcome.entries.filter { it != FeedFetchOutcome.FETCHED }.forEach { outcome ->
            val reg = SimpleMeterRegistry()
            val feed = withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

            feed.record(outcome)

            assertThat(reg.freshnessRecorded())
                .describedAs("%s must leave the feed stale", outcome)
                .isEqualTo(0.0)
            assertThat(reg.fetchCount(outcome)).describedAs("%s must be counted", outcome).isEqualTo(1.0)
        }
    }

    @Test
    fun `outcome and freshness cannot drift apart because one call does both`() {
        val reg = SimpleMeterRegistry()
        val feed = withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        // Whatever a caller does, the sum of the outcome counters is the number of attempts, and the
        // freshness flag is set iff at least one of them was FETCHED. There is no API by which a
        // caller could mark the feed fresh without saying what it fetched.
        feed.record(FeedFetchOutcome.HTTP_ERROR)
        feed.record(FeedFetchOutcome.EMPTY)
        assertThat(reg.freshnessRecorded()).isEqualTo(0.0)

        feed.record(FeedFetchOutcome.FETCHED)
        assertThat(reg.freshnessRecorded()).isEqualTo(1.0)
        assertThat(FeedFetchOutcome.entries.sumOf { reg.fetchCount(it) }).isEqualTo(3.0)
    }

    @Test
    fun `a feed registration is a no-op when no registry is resolvable`() {
        val inst = mockk<Instance<MeterRegistry>>()
        every { inst.isResolvable } returns false

        val feed = DomainMetrics().apply { registryInstance = inst }.registerFeedFetch(FEED, Duration.ofDays(1))

        // Must not throw: a money-path job must never fail because its observability wiring is absent.
        feed.record(FeedFetchOutcome.FETCHED)
        feed.record(FeedFetchOutcome.HTTP_ERROR)
    }

    // ── 2. the cold-pod (t=0) reading ───────────────────────────────────────────

    @Test
    fun `at t=0 on a cold pod the feed reads as young and unproven, never as decades stale`() {
        val reg = SimpleMeterRegistry()

        withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        // Registration seeds the age from `now`, so a fresh pod reports its own uptime. The bound is
        // deliberately loose — this pins "seconds, not an epoch offset", which is the only thing the
        // alert rule depends on. `WorkflowLivenessStale` fires at 2 * expected_interval = 172800s
        // for this daily feed, so a boot reading near zero cannot fire it.
        assertThat(reg.freshnessAgeSeconds())
            .describedAs(
                "an EPOCH-style seed reads ~1.8e9 here and would fire WorkflowLivenessStale 15 " +
                    "minutes after every deploy, for every daily feed, until its next success (#4208)",
            )
            .isGreaterThanOrEqualTo(0.0)
            .isLessThan(BOOT_AGE_CEILING_SECONDS)
        assertThat(reg.freshnessRecorded())
            .describedAs("nothing has been fetched yet, and that is a different claim from 'stale'")
            .isEqualTo(0.0)
    }

    // ── 3. the producer/consumer naming seam ────────────────────────────────────

    @Test
    fun `a real registration emits exactly the meter name the shared constant declares`() {
        val reg = SimpleMeterRegistry()

        withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        assertThat(reg.meters.map { it.id.name })
            .describedAs("the constant must describe what registerFeedFetch really emits")
            .contains(FeedFetchMetrics.FETCH_TOTAL)
        assertThat(
            reg.find(FeedFetchMetrics.FETCH_TOTAL).counters().map { it.id.getTag(FeedFetchMetrics.OUTCOME_TAG) },
        ).containsExactlyInAnyOrderElementsOf(FeedFetchOutcome.entries.map { it.name.lowercase() })
    }

    @Test
    fun `the declared PromQL series name is the one a real Prometheus scrape produces`() {
        val reg = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val feed = withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))
        feed.record(FeedFetchOutcome.HTTP_ERROR)

        val scraped = reg.scrape()

        // Asserted against the registry's own rendering, never against a local dot -> underscore
        // helper: the helper would only be proving itself, and it does not know that Micrometer
        // appends `_total` to a counter. `openbank_feed_fetch` — the answer a dots-only derivation
        // gives — matches no series at all, which is #2187 with an extra step.
        assertThat(scraped)
            .describedAs("FeedFetchMetrics.FETCH_TOTAL_SERIES must be queryable, not merely plausible")
            .contains(FeedFetchMetrics.FETCH_TOTAL_SERIES)
        assertThat(scraped).contains("""feed="$FEED"""", """outcome="http_error"""")
    }

    @Test
    fun `the naive dots-only derivation of the counter name would NOT match the scrape`() {
        // The known-positive for the test above: it must be able to fail. If `_total` were dropped
        // from the constant, the assertion above would still pass on a substring match, so the
        // wrong name is pinned here as genuinely absent as a complete series name.
        val reg = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        withRegistry(reg).registerFeedFetch(FEED, Duration.ofDays(1))

        val naive = WorkflowLivenessMetrics.promSeriesName(FeedFetchMetrics.FETCH_TOTAL)

        assertThat(naive).isEqualTo("openbank_feed_fetch")
        assertThat(reg.scrape().lines().filter { it.startsWith("$naive{") })
            .describedAs("nothing is scraped under the dots-only name — the `_total` suffix is not optional")
            .isEmpty()
    }

    @Test
    fun `the freshness workflow tag carries the ADR-0237 feed prefix`() {
        assertThat(FeedFetchMetrics.freshnessWorkflow(FEED)).isEqualTo("feed-$FEED")
        assertThat(FeedFetchMetrics.freshnessWorkflow(FEED))
            .describedAs(
                "the feed's freshness must be a DIFFERENT workflow series from the job's heartbeat, " +
                    "or 'the scheduler ran' and 'the feed delivered' cannot alert independently",
            )
            .isNotEqualTo("fx-cnb-ingestion")
    }

    private companion object {
        const val FEED = "cnb-daily-fixing"

        /** Generous vs a test's real runtime; the point is "seconds", not a tight bound. */
        const val BOOT_AGE_CEILING_SECONDS = 600.0
    }
}
