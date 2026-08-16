// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.infrastructure.schedule

import com.openbank.fx.application.port.`in`.CnbRateIngestionUseCase
import com.openbank.fx.application.port.`in`.IngestCnbFixingCommand
import com.openbank.fx.domain.feed.FeedFetchOutcome
import com.openbank.libs.observability.DomainMetrics
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

    // ADR-0237 point 2: a SEPARATE liveness entry from the scheduler heartbeat above, under the
    // `feed-` prefix, gated on the REAL fetch outcome rather than "the job ran without throwing".
    // `fx-cnb-ingestion` kept recording success through the whole #2204 outage — the ingestion
    // swallowed the parse failure into one log line and never reached a point where it could fail —
    // so this entry's recordSuccess() below is called ONLY for FeedFetchOutcome.FETCHED. A repeat
    // of #2204 now stales THIS gauge within its 2x-daily grace even while the scheduler heartbeat
    // above stays green, because the two answer different questions (issue #4743).
    private var feedLiveness: WorkflowLivenessRecorder? = null

    // ADR-0160 mechanism 3. Registered once at startup (CDI beans are singletons), not per-run —
    // matches DomainMetrics.registerOutboxBacklog's "call once" contract and the one pre-existing
    // adopter, StandingOrderExecutionScheduler. Before this, a fixing ingestion that stopped that stopped
    // running left NO runtime signal at all: this job has no metric, no watchdog and no alert rule of any
    // kind, so success and failure both ended in a log line (#2239).
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(1))
        feedLiveness = domainMetrics.registerWorkflowLiveness(FEED_WORKFLOW_NAME, Duration.ofDays(1))
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
        // The scheduler heartbeat below (`liveness`) intentionally still records on every path
        // that reaches this point without throwing OUT of the job — that is what ADR-0237 point 1
        // means by "the job executed", and swallowing this exception is what keeps the Quarkus
        // scheduler thread alive for tomorrow's run. What changed is that a run reaching here no
        // longer implies the FEED delivered anything: `outcome` below, and `feedLiveness`, answer
        // that question separately (issue #4743).
        // `var`, not `val`: the compiler cannot prove the try-block's assignment happens strictly
        // before any exception (recordSuccess()/log calls after it can themselves throw), so it
        // conservatively refuses a `val` reassigned from a catch block.
        var outcome: FeedFetchOutcome
        try {
            val result = useCase.ingest(IngestCnbFixingCommand(date = null))
            outcome = if (result.ingested + result.skipped > 0) FeedFetchOutcome.FETCHED else FeedFetchOutcome.EMPTY
            log.infof(
                "ČNB fixing ingested for %s (#%s): %d new, %d unchanged %s (outcome=%s)",
                result.date,
                result.sequence,
                result.ingested,
                result.skipped,
                result.currencies,
                outcome,
            )
            liveness?.recordSuccess()
            if (outcome == FeedFetchOutcome.FETCHED) {
                feedLiveness?.recordSuccess()
            } else {
                log.warnf(
                    "ČNB fixing feed answered but had nothing for the configured currencies — " +
                        "feed=%s outcome=%s",
                    FEED_NAME,
                    outcome,
                )
            }
        } catch (ex: IllegalArgumentException) {
            // CnbFixingParser's require()s — a 2xx body that is not the feed's declared shape,
            // including a "soft 404" (#2204: a 200 status carrying a 58 KB HTML error page).
            outcome = FeedFetchOutcome.PARSE_ERROR
            log.errorf(ex, "ČNB fixing ingestion failed to PARSE the fetched body: %s", ex.message)
        } catch (ex: Exception) {
            // Everything else originates below the parser: a non-2xx WebApplicationException from
            // the rest client, a transport failure, a @Timeout, or an open @CircuitBreaker — the
            // fetch itself, never its content.
            outcome = FeedFetchOutcome.HTTP_ERROR
            log.errorf(ex, "ČNB fixing ingestion failed to FETCH the feed: %s", ex.message)
        }
        domainMetrics.feedFetchOutcome(FEED_NAME, outcome.name)
    }

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "fx-cnb-ingestion"

        /** ADR-0237 point 2 feed name — matches [DomainMetrics.feedFetchOutcome]'s `feed` tag. */
        const val FEED_NAME = "cnb-fx-fixing"

        /** The `feed-` prefix is ADR-0237 point 2's convention for a feed-freshness liveness entry. */
        const val FEED_WORKFLOW_NAME = "feed-$FEED_NAME"
    }
}
