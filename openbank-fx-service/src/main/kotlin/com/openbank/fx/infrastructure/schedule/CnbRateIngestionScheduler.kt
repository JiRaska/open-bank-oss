// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.FeedFetchRecorder
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.time.Duration

/**
 * Daily job that ingests the ČNB central-bank fixing shortly after its ~14:30 Europe/Prague
 * publication (ADR-0046: 14:40). Ingestion is idempotent per business day, so a missed or repeated
 * run is harmless. Failures are logged and swallowed — the scheduler must never crash, and the
 * manual `POST /api/v1/fx/cnb/ingest` endpoint covers backfill.
 */
@ApplicationScoped
class CnbRateIngestionScheduler(
    private val useCase: CnbRateIngestionUseCase,
    private val domainMetrics: DomainMetrics,
) {
    private val log = Logger.getLogger(CnbRateIngestionScheduler::class.java)

    // Nullable, not `lateinit`: the gauge is a diagnostic, and a money-path job must never fail
    // because its observability wiring was not initialised. `lateinit` turns a missed StartupEvent
    // into an UninitializedPropertyAccessException thrown from the middle of the run.
    private var liveness: WorkflowLivenessRecorder? = null

    // ADR-0237 point 2 / #4743: the FEED's own signal, registered beside — never instead of — the
    // workflow heartbeat above. The heartbeat answers "did this job run"; a fetch can pass that test
    // while producing nothing usable, which is the entire ČNB incident (#2204: 404 for 46 days while
    // the downstream revaluation logged "no movement"). `feed-cnb-daily-fixing` advances only when
    // rates actually arrived, so the two gauges disagreeing IS the diagnosis, and the outcome
    // counter beside them says which of 404 / timeout / HTML-error-page / nothing-we-asked-for it is.
    private var feed: FeedFetchRecorder? = null

    // ADR-0160 mechanism 3. Registered once at startup (CDI beans are singletons), not per-run —
    // matches DomainMetrics.registerOutboxBacklog's "call once" contract and the one pre-existing
    // adopter, StandingOrderExecutionScheduler. Before this, a fixing ingestion that stopped that stopped
    // running left NO runtime signal at all: this job has no metric, no watchdog and no alert rule of any
    // kind, so success and failure both ended in a log line (#2239).
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
        feed = domainMetrics.registerFeedFetch(FEED_NAME, Duration.ofDays(1))
    }

    // `suspend`, never `runBlocking` (#2187, the fleet sweep of #2148). Quarkus invokes a plain
    // @Scheduled method on a bare `executor-thread`, which carries no Vert.x context, so
    // `runBlocking { useCase.ingest(…) }` ran the first reactive Panache query inside
    // (`FxRateRepository.findBySourceAndValidFrom`, via `sf.withSession`) off the event loop and
    // threw `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread`.
    // The catch below then swallowed it into a single ERROR line, so the daily ČNB fixing was
    // never once ingested and nothing else showed it. A suspending @Scheduled method is dispatched
    // by Quarkus on a proper (duplicated) Vert.x context instead.
    //
    // The cron is a config expression (same default as before) purely so an IT can shrink it and
    // drive the *real* scheduler dispatch — calling this method directly supplies a context the
    // scheduler does not, and would pass against the broken code.
    @Scheduled(
        cron = "{openbank.cnb.ingestion-cron:0 40 14 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun ingestDailyFixing() {
        try {
            val result = useCase.ingest(IngestCnbFixingCommand(date = null))
            log.infof(
                "ČNB fixing ingested for %s (#%s): %d new, %d unchanged %s",
                result.date,
                result.sequence,
                result.ingested,
                result.skipped,
                result.currencies,
            )
            // Success path only — the catch below is a failed run. Unchanged by #4743: this is the
            // JOB's heartbeat and the job did complete, including on the EMPTY outcome below. A run
            // that legitimately stored nothing is a run, not a miss (ADR-0237 point 1); it is the
            // feed's own gauge that must not advance for it.
            liveness?.recordSuccess()
            feed?.record(CnbFetchOutcomes.of(result))
        } catch (ex: Exception) {
            // observed-by: openbank_feed_fetch_* for FEED_NAME — `feed.record(ofFailure)` marks the
            // run failed, so the feed's own gauge stops advancing and CnbFeedStale fires. This is a
            // SCHEDULED job with no message to nack: there is nothing to hand back to a broker, and
            // the next cron re-fetches the same fixing, so the failure is not lost work. The gauge
            // is the signal, which is exactly the condition `observed-by:` exists to state (#5745).
            //
            // Record before logging: the metric is the signal that survives, and a log line is what
            // this failure has already been reduced to once, for 46 days.
            feed?.record(CnbFetchOutcomes.ofFailure(ex))
            log.errorf(ex, "ČNB fixing ingestion failed: %s", ex.message)
        }
    }

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "fx-cnb-ingestion"

        /**
         * ADR-0237 point 2 feed name — deliberately the same string the feed is declared under in
         * `.github/scripts/check-external-feeds.py`'s `FEEDS` list, so the CI probe that falsifies
         * the URL from outside and the gauge that measures freshness from inside name the same feed.
         */
        const val FEED_NAME = "cnb-daily-fixing"
    }
}
