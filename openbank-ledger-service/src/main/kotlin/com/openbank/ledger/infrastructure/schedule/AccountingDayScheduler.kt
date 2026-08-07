// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.AccountingDayUseCase
import com.openbank.ledger.application.port.`in`.OpenAccountingDayCommand
import com.openbank.ledger.application.port.`in`.TransitionAccountingDayCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.libs.persistence.lock.ClusterLock
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the accounting-day lifecycle automatically (ADR-0207 increment 2).
 *
 * Increment 1 shipped the state machine (`OPEN → CUTOFF → TIED_OUT → LOCKED`), the operator
 * surface and the day lock in shadow mode — and deliberately left days **operator-opened**. The
 * measured consequence on the live cluster (2026-08-07): `ledger_accounting_day` had zero rows
 * while postings flowed, so every day-lock decision was `no_day_record` and the shadow counter
 * `openbank_ledger_day_lock_decisions_total{outcome="would_refuse"}` could never fire — the
 * evidence gate for enforcement was structurally vacuous. A lock whose calendar nobody maintains
 * measures nothing; this scheduler is what makes the calendar real, and therefore what makes
 * flipping `openbank.ledger.day-lock.mode` to `enforce` an evidence-backed step instead of a leap.
 *
 * Each tick reconciles persisted day state toward what the [AccountingClock] says is true:
 *
 * 1. **Open** the current accounting day if it has no row, then backfill any gap since the
 *    latest known row (oldest first, bounded per tick like [TieOutScheduler]'s catch-up). On the
 *    very first tick ever there is no anchor and only the current day is opened — history before
 *    the scheduler existed is deliberately not fabricated: the day lock treats a day with no row
 *    as not-refusable in every mode, which is exactly right for days that predate the calendar.
 * 2. **`OPEN → CUTOFF`** for every open day the accounting clock has moved past. The current day
 *    stays OPEN.
 * 3. **`CUTOFF → TIED_OUT`** for a day whose *latest* tie-out run is OK **and** ran after the
 *    day's cutoff — evidence recorded before the day stopped moving proves nothing about its
 *    final figures. In the normal cadence this holds by construction: a day goes CUTOFF at the
 *    first tick after midnight, [TieOutScheduler] checks it at 06:00, and this scheduler advances
 *    it within [Scheduled] cadence of that. A BREAK or ERROR run leaves the day in CUTOFF, where
 *    the stuck gauge below makes it visible.
 * 4. **`TIED_OUT → LOCKED` is not automated here.** Locking is the statement/period-close act
 *    (ADR-0035/ADR-0096 territory), not a timer's: a day becomes evidence when downstream close
 *    processes have consumed it, which this scheduler cannot know. Operator-driven until that
 *    wiring exists.
 *
 * Every step is idempotent and per-day failures are contained: one day failing to transition is
 * logged and counted, the rest of the tick proceeds. The whole tick runs under [ClusterLock] for
 * the same reason [TieOutScheduler] does (#1201): an Argo Rollouts canary window runs two pods
 * whose ticks would otherwise both reconcile — the transitions are individually race-safe
 * (version-guarded UPDATE + unique business date), so the lock is about noise, not correctness.
 *
 * **Stuck-CUTOFF visibility.** ADR-0207's Consequences name the new failure mode this scheduler
 * introduces: a day stuck in CUTOFF because its tie-out never passed blocks that day's postings
 * once the lock enforces — so the ADR makes detection a precondition of enforcement.
 * `openbank.ledger.accounting_day.stuck_cutoff_days` gauges how many days have sat in CUTOFF
 * longer than the configured threshold; the paired PrometheusRule
 * (`openbank-infra/gitops/components/observability/prometheus-rules-accounting-day.yaml`) alerts
 * on it being non-zero. Normal residence in CUTOFF is ~6h (midnight tick → 06:00 tie-out), so
 * the default threshold of 8h means "the 06:00 run did not deliver an OK verdict".
 */
@ApplicationScoped
class AccountingDayScheduler(
    private val accountingDayRepository: AccountingDayRepository,
    private val tieOutRunRepository: TieOutRunRepository,
    private val accountingDayUseCase: AccountingDayUseCase,
    private val accountingClock: AccountingClock,
    private val clusterLock: ClusterLock,
    @ConfigProperty(name = "openbank.ledger.accounting-day.max-catchup-days", defaultValue = "7")
    private val maxCatchUpDays: Int,
    @ConfigProperty(name = "openbank.ledger.accounting-day.stuck-cutoff-hours", defaultValue = "8")
    private val stuckCutoffHours: Long,
) {
    private val log: Logger = Logger.getLogger(AccountingDayScheduler::class.java)

    // Field-injected to keep the constructor under detekt's LongParameterList threshold, the
    // same shape as LoanStageEventConsumer and McpEndpoint.
    @Inject
    lateinit var domainMetrics: DomainMetrics

    @Inject
    lateinit var meterRegistry: MeterRegistry

    // Nullable, not `lateinit` — a money-path job must never fail because its observability
    // wiring was not initialised (same reasoning as TieOutScheduler).
    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Updated every tick; read by the gauge. Starts at zero, which is also the boot state — the
     * alert rule tolerates that window because the stuck condition, if real, is re-published on
     * the next tick (minutes), while the alert's `for:` is longer.
     */
    private val stuckCutoffDays = AtomicInteger(0)

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        Gauge.builder(STUCK_GAUGE, stuckCutoffDays) { it.get().toDouble() }
            .description(
                "Accounting days sitting in CUTOFF longer than the configured threshold — the " +
                    "tie-out never delivered an OK verdict for them (ADR-0207). Non-zero pages.",
            )
            .strongReference(true)
            .register(meterRegistry)
    }

    // `suspend`, never `runBlocking` (#2187/#2148): a plain @Scheduled method runs on a bare
    // executor-thread with no Vert.x context and the first reactive call dies with HR000068.
    // The cron is a config expression so LedgerSchedulerVertxContextIT can shrink it and drive
    // the REAL scheduler dispatch — a direct call supplies the context the scheduler does not.
    @Scheduled(
        cron = "{openbank.ledger.accounting-day.cron:0 */15 * * * ?}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun reconcile() {
        val ran = clusterLock.tryRunExclusively(JOB_NAME) {
            val today = accountingClock.today()
            openMissingDays(today)
            cutOverPastDays(today)
            tieOutEligibleDays()
            publishStuckCutoff()
        }
        if (ran == null) {
            log.debugf("Accounting-day reconcile: another pod holds this tick's lock — skipping")
        } else {
            liveness?.recordSuccess()
        }
    }

    /**
     * Ensure a row exists for [today] and for every day between the latest known row and today,
     * oldest first, bounded by [maxCatchUpDays] per tick (forward progress on a long gap, same
     * shape as [TieOutScheduler.catchUpDates]). First tick ever: open [today] only.
     */
    private suspend fun openMissingDays(today: LocalDate) {
        val latest = accountingDayRepository.findLatest()
        val toOpen: List<LocalDate> = if (latest == null) {
            listOf(today)
        } else {
            generateSequence(latest.businessDate.plusDays(1)) { it.plusDays(1) }
                .takeWhile { it <= today }
                .take(maxCatchUpDays)
                .toList()
        }
        if (latest != null && toOpen.size == maxCatchUpDays && toOpen.last() < today) {
            log.warnf(
                "Accounting-day reconcile: gap since %s exceeds %d days — opening oldest-first, " +
                    "the remainder heals on subsequent ticks",
                latest.businessDate,
                maxCatchUpDays,
            )
        }
        toOpen.forEach { date ->
            runStep("open", date) {
                accountingDayUseCase.open(OpenAccountingDayCommand(businessDate = date, openedBy = SYSTEM_ACTOR))
                log.infof("Accounting day %s opened by scheduler", date)
            }
        }
    }

    /** Every OPEN day the clock has moved past goes to CUTOFF; the current day stays OPEN. */
    private suspend fun cutOverPastDays(today: LocalDate) {
        accountingDayRepository.findInStatus(AccountingDayStatus.OPEN)
            .filter { it.businessDate < today }
            .forEach { day ->
                runStep("cutoff", day.businessDate) {
                    accountingDayUseCase.transition(
                        TransitionAccountingDayCommand(
                            businessDate = day.businessDate,
                            to = AccountingDayStatus.CUTOFF,
                            transitionedBy = SYSTEM_ACTOR,
                        ),
                    )
                    log.infof("Accounting day %s cut off (accounting clock has moved past it)", day.businessDate)
                }
            }
    }

    /**
     * Advance CUTOFF days whose latest tie-out verdict is OK and was recorded after the cutoff.
     * The runAt-after-cutoff requirement is what makes TIED_OUT mean something: a verdict
     * produced while the day could still change is a snapshot, not evidence.
     */
    private suspend fun tieOutEligibleDays() {
        accountingDayRepository.findInStatus(AccountingDayStatus.CUTOFF).forEach { day ->
            runStep("tie-out", day.businessDate) {
                val run = tieOutRunRepository.findLatestFor(day.businessDate) ?: return@runStep
                val cutoffAt = day.cutoffAt ?: return@runStep
                if (run.status == TieOutRunStatus.OK && !run.runAt.isBefore(cutoffAt)) {
                    accountingDayUseCase.transition(
                        TransitionAccountingDayCommand(
                            businessDate = day.businessDate,
                            to = AccountingDayStatus.TIED_OUT,
                            transitionedBy = SYSTEM_ACTOR,
                        ),
                    )
                    log.infof(
                        "Accounting day %s tied out (tie-out run %s at %s)",
                        day.businessDate,
                        run.status,
                        run.runAt,
                    )
                }
            }
        }
    }

    /** Re-publish the count of days sitting in CUTOFF longer than the threshold. */
    private suspend fun publishStuckCutoff() {
        val threshold = accountingClock.instant().minus(Duration.ofHours(stuckCutoffHours))
        val stuck = accountingDayRepository.findInStatus(AccountingDayStatus.CUTOFF)
            .filter { day -> day.cutoffAt?.isBefore(threshold) == true }
        stuckCutoffDays.set(stuck.size)
        stuck.forEach { day: AccountingDayRecord ->
            log.warnf(
                "Accounting day %s has been in CUTOFF since %s (over %dh) — its tie-out has not " +
                    "delivered an OK verdict; investigate the 06:00 run, then drive the day " +
                    "forward via the operator API once resolved",
                day.businessDate,
                day.cutoffAt,
                stuckCutoffHours,
            )
        }
    }

    /**
     * One reconcile step for one day: failures are logged and contained so a single bad day
     * cannot stop the calendar for every other day. Racing pods and operator actions surface
     * here as conflicts, which the next tick resolves by re-reading state.
     */
    @Suppress("TooGenericExceptionCaught") // scheduler must survive any infra failure per tick
    private suspend fun runStep(step: String, date: LocalDate, block: suspend () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            log.errorf(ex, "Accounting-day reconcile step '%s' failed for %s: %s", step, date, ex.message)
        }
    }

    private companion object {
        const val JOB_NAME = "ledger.accounting-day"

        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "ledger-accounting-day"

        const val STUCK_GAUGE = "openbank.ledger.accounting_day.stuck_cutoff_days"

        /**
         * Actor recorded on scheduler-driven transitions. Distinct from any JWT principal so an
         * audit read can tell automation from an operator at a glance.
         */
        const val SYSTEM_ACTOR = "system:accounting-day-scheduler"

        /** The schedule interval the liveness gauge declares (sentinel fires at 2x this). */
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(15)
    }
}
