// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.port.out.CloseJobRunPort
import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Optional

/**
 * Monthly billing cycle sweep (ADR-0143 phase 2c). Derives a stable `cycleId` from the current
 * month (`yyyy-MM`, e.g. `"2026-07"`) using an injected [Clock] — **never** `Instant.now()`
 * directly (ADR-0100 DST rule, CI-enforced) — and delegates to [BillingCycleService.runCycle] for
 * every configured account.
 *
 * **Batch source** (ADR-0143 / issue #548 follow-up): the fleet-wide "list every billable
 * account" read port now exists — account-service's `GET /api/v1/accounts/active`, consumed via
 * [BillableAccountDiscoveryPort]. Because a discovered sweep charges every ACTIVE account in the
 * fleet, it is guarded by its own opt-in flag on top of `enabled`:
 *
 *  1. `openbank.billing.scheduler.account-ids` set → that operator-configured CSV is the batch
 *     (deliberate manual override; discovery is NOT consulted).
 *  2. CSV empty + `openbank.billing.scheduler.discovery-enabled=true` → the batch is discovered
 *     by paging `activeAccounts` (page size `discovery-page-size`), one `runCycle` per page so a
 *     large book never materializes in memory. A page-read failure aborts the sweep (logged
 *     error); the monthly re-run is idempotent per (cycleId, accountId, currency).
 *  3. Neither → safe no-op, same as before this port existed — the scheduler still cannot
 *     charge anyone by accident on default config.
 *
 * **Day-close evidence (ADR-0342 D1).** Every enabled sweep records a `billing_close_job_run` row
 * under the Prague accounting day it billed for — the same date the `cycleId` is derived from, taken
 * once at the start of the sweep, so a sweep crossing midnight is filed under the day it started on.
 * Accounts assessed are its items; accounts that failed to assess, or a sweep aborted by a failed
 * discovery page, are a refusal that holds the ledger's close. The fees' journals are booked later
 * by the outbox, so `BillingCloseParticipationScheduler` adds their posting status when it reports
 * the day. The cron fires in [BillingCloseJobs.SCHEDULE_ZONE], the zone the reporter evaluates it in.
 */
@ApplicationScoped
class BillingCycleScheduler {

    @Inject
    lateinit var billingCycleService: BillingCycleService

    @Inject
    lateinit var clock: Clock

    @ConfigProperty(name = "openbank.billing.scheduler.enabled", defaultValue = "false")
    var enabled: Boolean = false

    // Optional<String>, not a plain String (CLAUDE.md pitfall): a missing/empty-default optional
    // config property typed as bare String throws SRCFG00040 / ConfigurationException at boot —
    // this exact defect surfaced in CI (BillingResourceAuthzTest's @QuarkusTest container failed
    // to deploy). No account-ids configured is the safe, expected default (see class KDoc).
    @ConfigProperty(name = "openbank.billing.scheduler.account-ids")
    lateinit var accountIdsCsv: Optional<String>

    @ConfigProperty(name = "openbank.billing.scheduler.currency", defaultValue = "CZK")
    lateinit var currency: String

    /** Opt-in for the fleet-wide discovered sweep (see class KDoc rule 2) — default OFF. */
    @ConfigProperty(name = "openbank.billing.scheduler.discovery-enabled", defaultValue = "false")
    var discoveryEnabled: Boolean = false

    @ConfigProperty(name = "openbank.billing.scheduler.discovery-page-size", defaultValue = "100")
    var discoveryPageSize: Int = DEFAULT_DISCOVERY_PAGE_SIZE

    @Inject
    lateinit var accountDiscovery: BillableAccountDiscoveryPort

    @Inject
    lateinit var domainMetrics: DomainMetrics

    @Inject
    lateinit var closeJobRuns: CloseJobRunPort

    private val log = Logger.getLogger(BillingCycleScheduler::class.java)

    private var liveness: WorkflowLivenessRecorder? = null

    /**
     * Set by [runDiscoveredSweep] when a page fetch throws. Without it an aborted sweep would
     * still record a success, because that method swallows the failure and returns — which would
     * make the liveness gauge report the scheduler's heartbeat instead of the workflow's outcome,
     * the exact substitution that let the #2187 jobs look alive while doing nothing.
     */
    private var aborted = false

    /**
     * One sweep at a time IN THIS JVM, across the cron trigger and the catch-up. Deliberately not a
     * cluster lock, and safe because of two facts, both checked when this was written (#13709 review):
     *  - billing runs as ONE replica (`gitops/components/billing/billing-service.yaml`: a Deployment,
     *    `replicas: 1`), so in steady state there is no second JVM to overlap with;
     *  - during a rolling update two pods can briefly coexist, and a double sweep then costs duplicate
     *    work, never a double charge: an assessment is unique per (cycle, account, currency) and
     *    `persistWithPostingIntent` recovers the unique-constraint race into an idempotent replay, so
     *    the second sweep re-reads the first one's rows.
     * A cluster lock (libs `PostgresClusterLock`) was not used: it runs its block inside ONE
     * transaction, which the per-account `sf.withTransaction` calls would join, turning one account's
     * failure into a rollback of the whole batch. Scaling billing past one replica must revisit this.
     */
    private val sweepInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @ConfigProperty(name = "openbank.billing.scheduler.cron")
    lateinit var cron: String

    /** Accounts assessed / failed by the sweep in flight — the day-close evidence it records. */
    private var processedInSweep = 0
    private var failedInSweep = 0

    // ADR-0160 mechanism 3, registered once at startup (#2239). Before this the monthly cycle had
    // no metric, no watchdog and no alert rule of any kind — success and failure both ended in a
    // log line, so a cycle that stopped running was invisible.
    //
    // Registered ONLY when the sweep is enabled, unlike the always-on jobs: this scheduler is
    // opt-in and OFF by default, so a gauge in a deployment that deliberately does not run the
    // cycle would read as maximally stale forever and manufacture exactly the false finding the
    // liveness mechanism exists to make trustworthy.
    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") ev: StartupEvent) {
        if (enabled) {
            liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofDays(EXPECTED_INTERVAL_DAYS))
        }
    }

    // `suspend`, never `runBlocking` (#2187, the fleet sweep of #2148). Quarkus invokes a plain
    // @Scheduled method on a bare `executor-thread`, which carries no Vert.x context, so
    // `runBlocking { runSweep() }` ran the first reactive Panache call reached from it
    // (`BillingAssessmentRepositoryImpl.findExisting`, via `sf.withSession`) off the event loop and
    // threw `HR000068: This method should exclusively be invoked from a Vert.x EventLoop thread`,
    // aborting the whole monthly cycle. It stayed latent only because the sweep is off by default
    // and no-ops without a configured batch — the first environment to switch it on would have
    // found a cycle that silently assessed nothing. A suspending @Scheduled method is dispatched by
    // Quarkus on a proper (duplicated) Vert.x context instead.
    @Scheduled(
        cron = "{openbank.billing.scheduler.cron}",
        timeZone = BillingCloseJobs.SCHEDULE_ZONE,
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sweep() = runSweep()

    /**
     * Catch-up for a missed cycle (#13709 review). The cron fires at one instant a month, so a pod that
     * is down at that instant — a deploy on the 1st, or the move of the trigger to Europe/Prague, which
     * shifts that instant by an hour or two against the old UTC firing — skips the whole month with
     * nothing to say so. Every tick (and two minutes after boot) this checks whether the cron's most
     * recent firing falls in the current month and the month has no recorded cycle run; if so it runs
     * the sweep for THAT due day. Idempotent by `cycleId` (the assessment is unique per cycle, account
     * and currency), and a run in flight is never doubled ([sweepInFlight]).
     */
    @Scheduled(
        every = "{openbank.billing.scheduler.catch-up-every:15m}",
        delayed = "2m",
        identity = "billing-cycle-catch-up",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun catchUp() {
        if (!enabled) return
        val due = missedDueDay() ?: return
        log.warnf(
            "[billing-cycle-scheduler] cycle %s has no run although it was due on %s - catching up",
            cycleIdFor(due),
            due,
        )
        runSweep(due)
    }

    /** The current month's due day when the cron has already fired this month and no run is recorded. */
    @Suppress("TooGenericExceptionCaught") // an unreadable cron or evidence store must not fire a cycle
    internal suspend fun missedDueDay(): LocalDate? {
        val zone = AccountingClock.bank(clock).zone
        val now = clock.instant().atZone(zone)
        val last = try {
            ExecutionTime.forCron(CRON_PARSER.parse(cron)).lastExecution(now).orElse(null)
        } catch (e: Exception) {
            log.warnf("[billing-cycle-scheduler] catch-up skipped: cron not evaluable (%s)", e.javaClass.simpleName)
            null
        } ?: return null
        val today = now.toLocalDate()
        val dueDay = last.toLocalDate()
        if (dueDay.year != today.year || dueDay.month != today.month) return null
        val recorded = try {
            closeJobRuns.findBetween(today.withDayOfMonth(1), today).awaitSuspending()
        } catch (e: Exception) {
            log.warnf(
                "[billing-cycle-scheduler] catch-up skipped: run evidence unreadable (%s)",
                e.javaClass.simpleName,
            )
            return null
        }
        return if (recorded.any { it.job == BillingCloseJobs.CYCLE }) null else dueDay
    }

    /**
     * The cycle-sweep logic, split out from the `@Scheduled` entrypoint so unit tests can drive it
     * directly. Note that a direct call is *not* sufficient coverage for the entrypoint itself: it
     * supplies a Vert.x context the Quarkus scheduler does not, which is exactly how #2187 hid —
     * see `BillingCycleSweepVertxContextIT`, which drives the real cron instead.
     */
    suspend fun runSweep(dueDay: LocalDate? = null) {
        if (!enabled) {
            log.debug("[billing-cycle-scheduler] Disabled — skipping sweep")
            return
        }
        // One sweep at a time across the cron trigger and the catch-up (each is SKIP on its own only).
        if (!sweepInFlight.compareAndSet(false, true)) {
            log.info("[billing-cycle-scheduler] a sweep is already running - skipping")
            return
        }
        try {
            sweepFor(dueDay ?: AccountingClock.bank(clock).today())
        } finally {
            sweepInFlight.set(false)
        }
    }

    private suspend fun sweepFor(businessDate: LocalDate) {
        // The Prague accounting day (ADR-0207), never the UTC date: the cycle id and the day-close
        // evidence are derived from the same date, so they cannot disagree about the month or day.
        // A catch-up passes the day the cycle was DUE, so its evidence is filed where the reporter
        // expects it.
        val cycleId = cycleIdFor(businessDate)
        processedInSweep = 0
        failedInSweep = 0
        val accountIds = accountIdsCsv.orElse("").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        when {
            // Rule 1 (class KDoc): an operator-configured CSV is a deliberate manual override.
            accountIds.isNotEmpty() -> {
                log.infof(
                    "[billing-cycle-scheduler] Starting billing cycle %s for %d configured account(s)",
                    cycleId,
                    accountIds.size,
                )
                val outcome = billingCycleService.runCycleWithOutcome(cycleId, accountIds, currency)
                processedInSweep += outcome.processed
                failedInSweep += outcome.failed
                val processed = outcome.processed
                log.infof(
                    "[billing-cycle-scheduler] Billing cycle %s done: %d account(s) processed",
                    cycleId,
                    processed,
                )
            }
            discoveryEnabled -> runDiscoveredSweep(cycleId)
            else -> log.debug("[billing-cycle-scheduler] No accounts configured, discovery off — skipping sweep")
        }
        // The cycle completed. runDiscoveredSweep returns early on abort, so an aborted sweep
        // falls through here — see the guard below.
        if (!aborted) liveness?.recordSuccess()
        recordCloseEvidence(businessDate, aborted)
        aborted = false
    }

    /**
     * Never fails the sweep: a lost record only means the day is not reported, which holds the
     * ledger's close and pages — the safe direction.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordCloseEvidence(businessDate: LocalDate, aborted: Boolean) {
        val refused = (if (aborted) 1 else 0) + failedInSweep
        val reason = when {
            aborted -> BillingCloseJobs.REASON_SWEEP_ABORTED
            failedInSweep > 0 -> BillingCloseJobs.REASON_ACCOUNTS_FAILED
            else -> null
        }
        try {
            closeJobRuns.record(
                CloseJobRun(
                    businessDate = businessDate,
                    job = BillingCloseJobs.CYCLE,
                    itemsProcessed = processedInSweep,
                    journalsPosted = 0,
                    refused = refused,
                    refusalReason = reason,
                    completedAt = clock.instant(),
                ),
            ).awaitSuspending()
        } catch (e: Exception) {
            log.warnf("[billing-cycle-scheduler] day-close evidence NOT recorded (%s)", e.javaClass.simpleName)
        }
    }

    /** Rule 2 (class KDoc): page through account-service's ACTIVE-account sweep, one cycle per page. */
    @Suppress("TooGenericExceptionCaught") // a failed sweep must abort with ONE clear log line, whatever threw
    private suspend fun runDiscoveredSweep(cycleId: String) {
        log.infof("[billing-cycle-scheduler] Starting billing cycle %s via account discovery", cycleId)
        var cursor: String? = null
        var pages = 0
        var processed = 0
        try {
            do {
                val page = accountDiscovery.activeAccounts(discoveryPageSize, cursor)
                if (page.accountIds.isNotEmpty()) {
                    val outcome = billingCycleService.runCycleWithOutcome(cycleId, page.accountIds, currency)
                    processed += outcome.processed
                    processedInSweep += outcome.processed
                    failedInSweep += outcome.failed
                    pages++
                }
                cursor = page.nextCursor
            } while (cursor != null)
        } catch (e: Exception) {
            // Abort, don't swallow-and-continue: the monthly re-run is idempotent per
            // (cycleId, accountId, currency), so the safe move is to stop and retry whole.
            log.errorf(
                e,
                "[billing-cycle-scheduler] Billing cycle %s aborted after %d page(s), %d account(s) processed",
                cycleId,
                pages,
                processed,
            )
            aborted = true
            return
        }
        log.infof(
            "[billing-cycle-scheduler] Billing cycle %s done: %d account(s) processed across %d page(s)",
            cycleId,
            processed,
            pages,
        )
    }

    companion object {
        /** ADR-0160 mechanism 3 workflow tag — stable, low-cardinality. */
        const val WORKFLOW_NAME = "billing-cycle"

        /** The cycle runs monthly (`0 0 3 1 * ?`); the liveness rule fires at 2x this. */
        const val EXPECTED_INTERVAL_DAYS = 31L

        /** Matches account-service's own default page size for the /accounts/active sweep. */
        const val DEFAULT_DISCOVERY_PAGE_SIZE = 100

        private val CRON_PARSER = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

        private val CYCLE_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

        /** The billing cycle id for a date: the calendar month, e.g. `2026-07`. */
        fun cycleIdFor(date: LocalDate): String = date.format(CYCLE_ID_FORMAT)
    }
}
