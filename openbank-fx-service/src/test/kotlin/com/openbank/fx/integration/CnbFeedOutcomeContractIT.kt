// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.integration

import com.openbank.fx.application.port.out.CnbRateProvider
import com.openbank.fx.it.PostgresRedisTestResource
import com.openbank.libs.observability.FeedFetchMetrics
import com.openbank.libs.observability.FeedFetchOutcome
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The fetch-outcome contract, end to end, on the case that had no signal at all (#4743, ADR-0237
 * point 2).
 *
 * The stub feed below is **healthy in every way a job can measure**: it answers, it is well-formed,
 * `CnbFixingParser` accepts it, the use case returns normally, and the scheduler logs an INFO line
 * and records its own success. The only thing wrong with it is that it carries no rate for the
 * currency this service is configured to ingest — an upstream that renamed or dropped a code. That
 * is the state this repo keeps meeting in different costumes (`PushResult.skipped()` counting
 * undelivered pushes as delivered, #4348): a no-op that reports as a success, raising no error
 * anywhere, so no error-rate alert can ever see it.
 *
 * **What this asserts that a unit test cannot.** Two things.
 *
 * First, the dispatch: the profile shrinks the real cron to two seconds so Quarkus invokes the
 * `@Scheduled` method itself. Calling `ingestDailyFixing()` from a test supplies a Vert.x context
 * the scheduler does not, and that is precisely how this job shipped broken once already (#2187) —
 * `CnbRateIngestionSchedulerTest` passed against code that had never ingested a single rate.
 *
 * Second, and the point of the contract: the two liveness series are asserted **against each
 * other**. `fx-cnb-ingestion` (the job ran) must read succeeded, while `feed-cnb-daily-fixing` (the
 * feed delivered) must read not-yet — simultaneously, in one process, from one run. Either series
 * alone is consistent with a healthy system; only their disagreement names the failure, and before
 * this PR the second series did not exist.
 *
 * Note what the freshness assertions discriminate BY: `openbank.workflow.success.recorded` (0/1),
 * never the age gauge's magnitude. The age is seeded at registration (#4208), so on a pod this young
 * a delivering feed and a dead one read the same small number — an age-threshold assertion here
 * would pass for the wrong reason and keep passing if the contract were removed.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestProfile(CnbFeedOutcomeContractIT.EmptyFeedProfile::class)
class CnbFeedOutcomeContractIT {

    /**
     * Drives the real cron every two seconds against a feed that parses cleanly and carries nothing
     * this service wants.
     *
     * Every value is a **literal**, not a reference to the companion object below: a
     * [QuarkusTestProfile] is loaded in a different classloader from the test class, so the
     * companion initialises twice and a value read here is not guaranteed to be the value the
     * assertions read.
     */
    class EmptyFeedProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "quarkus.scheduler.enabled" to "true",
            "openbank.cnb.ingestion-cron" to "*/2 * * * * ?",
            // The stub feed carries EUR and JPY. Asking for a currency it does not publish is the
            // whole scenario — nothing errors, and nothing is ingested.
            "openbank.cnb.currencies" to "XAU",
            "openbank.outbox.dispatch-enabled" to "false",
        )

        override fun getEnabledAlternatives(): MutableSet<Class<*>> = mutableSetOf(EmptyForUsCnbProvider::class.java)
    }

    /**
     * A perfectly valid ČNB fixing — correct header, correct separators, real rate lines. It simply
     * does not contain the configured currency. No status code, no exception, no malformed byte.
     */
    @Alternative
    @ApplicationScoped
    class EmptyForUsCnbProvider : CnbRateProvider {
        override suspend fun fetchFixing(date: LocalDate?): String =
            """
            30.05.2026 #104
            země|měna|množství|kód|kurz
            EMU|euro|1|EUR|25,145
            Japonsko|jen|100|JPY|14,621
            """.trimIndent()
    }

    @Inject
    lateinit var registry: MeterRegistry

    private fun fetchCount(outcome: FeedFetchOutcome): Double = registry.find(FeedFetchMetrics.FETCH_TOTAL)
        .tag(FeedFetchMetrics.FEED_TAG, FEED)
        .tag(FeedFetchMetrics.OUTCOME_TAG, outcome.name.lowercase())
        .counter()
        ?.count() ?: 0.0

    private fun successRecorded(workflow: String): Double = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag(WorkflowLivenessMetrics.WORKFLOW_TAG, workflow)
        .gauge()
        ?.value() ?: -1.0

    @Test
    fun `a feed that fetches on schedule and delivers nothing reads as EMPTY, and its freshness never advances`() {
        val deadline = System.nanoTime() + BUDGET_NANOS
        while (fetchCount(FeedFetchOutcome.EMPTY) < 1.0 && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }

        assertThat(fetchCount(FeedFetchOutcome.EMPTY))
            .describedAs(
                "the scheduler-dispatched run parsed a valid fixing carrying none of the configured " +
                    "currencies; that must be counted as EMPTY. Zero here means either the cron " +
                    "never fired (#2187) or the outcome was misclassified as a success.",
            )
            .isGreaterThanOrEqualTo(1.0)

        assertThat(fetchCount(FeedFetchOutcome.FETCHED))
            .describedAs("nothing was delivered, so nothing may be counted as delivered (#4348's shape)")
            .isZero()

        assertThat(successRecorded(FeedFetchMetrics.freshnessWorkflow(FEED)))
            .describedAs(
                "the feed's own freshness must stay unproven, so that openbank_workflow_last_success_" +
                    "age_seconds{workflow=\"feed-$FEED\"} keeps growing and WorkflowLivenessStale " +
                    "eventually fires — the alert that did not exist for the 46-day ČNB 404",
            )
            .isZero()

        assertThat(successRecorded(WORKFLOW))
            .describedAs(
                "and the JOB's heartbeat must be green at the same moment: the scheduler did run and " +
                    "did complete. Two series disagreeing is the diagnosis; a run/no-run signal alone " +
                    "reports this whole scenario as healthy.",
            )
            .isEqualTo(1.0)
    }

    @Test
    fun `every outcome is published at zero rather than absent, so triage can distinguish never from unwired`() {
        val counters = FeedFetchOutcome.entries.map { outcome ->
            registry.find(FeedFetchMetrics.FETCH_TOTAL)
                .tag(FeedFetchMetrics.FEED_TAG, FEED)
                .tag(FeedFetchMetrics.OUTCOME_TAG, outcome.name.lowercase())
                .counter()
        }

        assertThat(counters)
            .describedAs("a lazily created counter makes 'never 404'd' and 'never instrumented' the same empty vector")
            .doesNotContainNull()
    }

    private companion object {
        const val FEED = "cnb-daily-fixing"
        const val WORKFLOW = "fx-cnb-ingestion"

        /** Generous vs the 2 s cron so a slow CI runner cannot flake the wait. */
        const val BUDGET_NANOS = 60_000_000_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
