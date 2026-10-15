// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbPolicyRateUseCase
import com.openbank.fx.domain.cnb.CnbPolicyInstrument
import com.openbank.fx.infrastructure.observability.CnbPolicyRateMetrics
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.FeedFetchOutcome
import com.openbank.libs.observability.FeedFetchRecorder
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Downloads and ingests the ČNB policy-rate histories (2W repo, discount, lombard) and the
 * minimum-reserve workbook (reserve ratio + remuneration). Runs daily after the ČNB's usual 14:30 publication, AND shortly after
 * every boot, so a fresh deployment (or one that missed the cron while down) does not wait up to a
 * day for its first data. Every run re-reads the whole history; the upsert is idempotent, so a
 * repeated or overlapping run stores nothing new.
 *
 * Each feed is attempted independently and records its OWN feed outcome — one dead URL must not
 * hide the others. The workflow heartbeat advances only when every feed succeeded.
 */
@ApplicationScoped
class CnbPolicyRateIngestionScheduler(
    private val useCase: CnbPolicyRateUseCase,
    private val domainMetrics: DomainMetrics,
    private val rowMetrics: CnbPolicyRateMetrics,
) {
    private val log = Logger.getLogger(CnbPolicyRateIngestionScheduler::class.java)

    // Nullable, not lateinit: same reasoning as CnbRateIngestionScheduler — observability wiring must
    // never be the thing that fails a money-path job.
    private var liveness: WorkflowLivenessRecorder? = null
    private var feeds: Map<CnbPolicyInstrument, FeedFetchRecorder> = emptyMap()
    private var minReservesFeed: FeedFetchRecorder? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
        feeds = FEED_NAMES.mapValues { (_, name) -> domainMetrics.registerFeedFetch(name, Duration.ofDays(1)) }
        minReservesFeed = domainMetrics.registerFeedFetch(FEED_MIN_RESERVES, Duration.ofDays(1))
    }

    // `suspend`, never `runBlocking` (#2187): a suspending @Scheduled method is dispatched on a
    // Vert.x context, which the reactive Hibernate session needs. Two triggers on one method: the
    // daily cron, and a boot-time run `initial-delay` after start that then repeats every
    // `startup-every` (default 24h — a harmless idempotent second daily run). Both are config
    // expressions so tests can switch them `off` and an IT can shrink them.
    @Scheduled(
        cron = "{openbank.cnb.policy-rates.ingestion-cron:0 45 14 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    @Scheduled(
        every = "{openbank.cnb.policy-rates.startup-every:24h}",
        delayed = "{openbank.cnb.policy-rates.initial-delay:30s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    // Each step records its own outcome and never throws, so the scheduler thread never sees one.
    suspend fun ingestPolicyRates() {
        var allFetched = true
        for (instrument in CnbPolicyInstrument.FEED_BACKED) {
            allFetched = ingestOne(instrument) && allFetched
        }
        allFetched = ingestMinimumReserves() && allFetched
        if (allFetched) liveness?.recordSuccess()
    }

    @Suppress("TooGenericExceptionCaught") // classified by CnbFetchOutcomes, never rethrown
    private suspend fun ingestMinimumReserves(): Boolean = try {
        val outcomes = useCase.ingestMinimumReserves()
        outcomes.forEach { (instrument, outcome) ->
            rowMetrics.record(instrument, outcome.counts)
            log.infof(
                "ČNB %s ingested from the minimum-reserve workbook: %d new, %d unchanged, %d revised",
                instrument,
                outcome.counts.inserted,
                outcome.counts.unchanged,
                outcome.counts.revised,
            )
        }
        minReservesFeed?.record(FeedFetchOutcome.FETCHED)
        true
    } catch (ex: Exception) {
        // observed-by: openbank_feed_fetch_total{feed=FEED_MIN_RESERVES}; the freshness gauge stops
        // advancing and CnbPolicyRateFeedStale fires. The whole workbook is re-read next run.
        minReservesFeed?.record(CnbFetchOutcomes.ofFailure(ex))
        log.errorf(ex, "ČNB minimum-reserve workbook ingestion failed: %s", ex.message)
        false
    }

    @Suppress("TooGenericExceptionCaught") // classified by CnbFetchOutcomes, never rethrown
    private suspend fun ingestOne(instrument: CnbPolicyInstrument): Boolean = try {
        val outcome = useCase.ingest(instrument)
        rowMetrics.record(instrument, outcome.counts)
        feeds[instrument]?.record(FeedFetchOutcome.FETCHED)
        log.infof(
            "ČNB %s history ingested: %d new, %d unchanged, %d revised, %d published",
            instrument,
            outcome.counts.inserted,
            outcome.counts.unchanged,
            outcome.counts.revised,
            outcome.published,
        )
        true
    } catch (ex: Exception) {
        // observed-by: openbank_feed_fetch_total{feed=<FEED_NAMES[instrument]>} — the outcome
        // counter names the failure class and the feed's freshness gauge stops advancing, which is
        // what CnbPolicyRateFeedStale alerts on. A scheduled job has nothing to nack; the next run
        // re-fetches the full history, so a failed run loses no data.
        feeds[instrument]?.record(CnbFetchOutcomes.ofFailure(ex))
        log.errorf(ex, "ČNB %s history ingestion failed: %s", instrument, ex.message)
        false
    }

    internal companion object {
        /** ADR-0160 workflow tag. */
        const val WORKFLOW_NAME = "fx-cnb-policy-rate-ingestion"

        /** Feed names — the same strings `check-external-feeds.py` declares for these URLs. */
        const val FEED_REPO = "cnb-policy-rate-repo"
        const val FEED_DISCOUNT = "cnb-policy-rate-discount"
        const val FEED_LOMBARD = "cnb-policy-rate-lombard"
        const val FEED_MIN_RESERVES = "cnb-policy-rate-min-reserves"

        val FEED_NAMES: Map<CnbPolicyInstrument, String> = mapOf(
            CnbPolicyInstrument.REPO_2W to FEED_REPO,
            CnbPolicyInstrument.DISCOUNT to FEED_DISCOUNT,
            CnbPolicyInstrument.LOMBARD to FEED_LOMBARD,
        )
    }
}
