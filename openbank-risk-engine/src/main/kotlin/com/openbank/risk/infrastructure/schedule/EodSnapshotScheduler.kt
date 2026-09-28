// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.schedule

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * End-of-day balance-sheet snapshot (ADR-0314): once per business day, drives
 * [SnapshotUseCase.createSnapshot] for today's as-of date so a snapshot exists without an operator
 * having to call `POST /api/v1/risk/snapshots` by hand.
 *
 * Gated behind `openbank.risk.eod-snapshot.enabled` (default `false`, ADR-0314 shipped operator
 * -driven; this scheduler is opt-in per environment, same shape as
 * `openbank.risk.lending.enabled`). Cron is `openbank.risk.eod-snapshot.cron`, timezone
 * `Europe/Prague` — the same property-naming and zone convention as
 * [com.openbank.ledger.infrastructure.schedule.AccountingDayScheduler]'s
 * `openbank.ledger.accounting-day.cron`.
 *
 * **Replay-idempotent by construction, not by anything this scheduler adds.** [SnapshotService]
 * (the only [SnapshotUseCase] implementation) keys a run on `(asOf, inputHash)`
 * (`SnapshotRepository.findByNaturalKey`) and returns the EXISTING run with `replayed = true` when
 * one is already stored for the same as-of and the same ledger knowledge — calling this tick twice
 * for the same business day, or replaying a whole day's ticks after a restart, produces at most one
 * stored run. That is also this scheduler's no-op signal: a `replayed` outcome is not an error, and
 * it does NOT share a flag with a freshly created run (`PushResult.skipped()` carrying
 * `success = true` is exactly the shape this avoids, CLAUDE.md's "successful no-op" bullet) — the
 * outcome tag on [RUNS_COUNTER] is `created` or `replayed`, two distinct values, never a boolean.
 *
 * `suspend`, never `runBlocking`: a plain (non-`suspend`) `@Scheduled` method carries no Vert.x
 * context, so a reactive call inside `runBlocking` throws `HR000068` and the tick silently does
 * nothing (#2148/#2187).
 *
 * **No `ClusterLock`, deliberately.** The fleet's `PostgresClusterLock` runs on Hibernate Reactive
 * Panache, which this service does not ship (it uses a raw Vert.x `Pool`), so injecting it would
 * fail at runtime. Two pods ticking together (an Argo Rollouts canary window) is still safe:
 * `SnapshotRepository.saveIfAbsent` is idempotent on `(asOf, inputHash)` and returns the run a
 * concurrent writer committed first, so the loser simply reports `replayed`.
 */
@ApplicationScoped
class EodSnapshotScheduler(
    private val snapshotUseCase: SnapshotUseCase,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.risk.eod-snapshot.enabled", defaultValue = "false")
    private val enabled: Boolean,
) {
    private val log: Logger = Logger.getLogger(EodSnapshotScheduler::class.java)

    // Field-injected to keep the constructor under detekt's LongParameterList threshold (9),
    // same shape as AccountingDayScheduler/LoanStageEventConsumer/McpEndpoint.
    @Inject
    lateinit var domainMetrics: DomainMetrics

    @Inject
    lateinit var meterRegistry: MeterRegistry

    // Nullable, not `lateinit` — a job must never fail because its observability wiring was not
    // initialised (same reasoning as AccountingDayScheduler/CompliancePackRefresher).
    private var liveness: WorkflowLivenessRecorder? = null
    private var createdCounter: Counter? = null
    private var replayedCounter: Counter? = null

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        createdCounter = Counter.builder(RUNS_COUNTER)
            .tag("outcome", "created")
            .description(
                "End-of-day balance-sheet snapshot ticks (ADR-0314), by outcome. 'created' is a " +
                    "genuinely new run; 'replayed' is the no-op path (a run already existed for " +
                    "today's as-of and input hash) and is NOT an error — the two are distinct tag " +
                    "values, never a shared success flag.",
            )
            .register(meterRegistry)
        replayedCounter = Counter.builder(RUNS_COUNTER)
            .tag("outcome", "replayed")
            .register(meterRegistry)
    }

    @Scheduled(
        cron = "{openbank.risk.eod-snapshot.cron:0 30 22 * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun createEodSnapshot() {
        if (!enabled) {
            log.debugf("EOD snapshot scheduler tick skipped — openbank.risk.eod-snapshot.enabled is false")
            return
        }
        runOnce()
    }

    /**
     * The tick's actual work, split out from [createEodSnapshot] so a unit test can drive it
     * directly (fast, no Quarkus, no scheduler dispatch) while the `@Scheduled`
     * method above is exercised only by the Vert.x-context integration test.
     */
    internal suspend fun runOnce() {
        val asOf = ZonedDateTime.now(clock).withZoneSameInstant(PRAGUE_ZONE).toLocalDate()
        val outcome = snapshotUseCase.createSnapshot(asOf)
        if (outcome.replayed) {
            replayedCounter?.increment()
            log.infof(
                "EOD snapshot for %s already existed (run %s, no-op replay) — nothing to do",
                asOf,
                outcome.run.id,
            )
        } else {
            createdCounter?.increment()
            log.infof("EOD snapshot for %s created (run %s, status %s)", asOf, outcome.run.id, outcome.run.status)
        }
        // The tick reached its use case either way (created or replayed): both are a working
        // scheduler, so both count as liveness. Only a thrown exception — left uncaught here on
        // purpose — withholds recordSuccess() and lets the age gauge grow, exactly the fleet
        // convention (AccountingDayScheduler records success only when the step completed).
        liveness?.recordSuccess()
    }

    private companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "risk-engine-eod-snapshot"

        const val RUNS_COUNTER = "openbank.risk.eod_snapshot.runs"

        /** The schedule interval the liveness gauge declares (sentinel fires at 2x this). */
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)

        val PRAGUE_ZONE: ZoneId = ZoneId.of("Europe/Prague")
    }
}
