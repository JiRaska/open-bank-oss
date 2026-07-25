// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.usecase.BillingCycleService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
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

    private val log = Logger.getLogger(BillingCycleScheduler::class.java)

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
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun sweep() = runSweep()

    /**
     * The cycle-sweep logic, split out from the `@Scheduled` entrypoint so unit tests can drive it
     * directly. Note that a direct call is *not* sufficient coverage for the entrypoint itself: it
     * supplies a Vert.x context the Quarkus scheduler does not, which is exactly how #2187 hid —
     * see `BillingCycleSweepVertxContextIT`, which drives the real cron instead.
     */
    suspend fun runSweep() {
        if (!enabled) {
            log.debug("[billing-cycle-scheduler] Disabled — skipping sweep")
            return
        }
        val cycleId = cycleIdFor(LocalDate.now(clock))
        val accountIds = accountIdsCsv.orElse("").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        when {
            // Rule 1 (class KDoc): an operator-configured CSV is a deliberate manual override.
            accountIds.isNotEmpty() -> {
                log.infof(
                    "[billing-cycle-scheduler] Starting billing cycle %s for %d configured account(s)",
                    cycleId,
                    accountIds.size,
                )
                val processed = billingCycleService.runCycle(cycleId, accountIds, currency)
                log.infof(
                    "[billing-cycle-scheduler] Billing cycle %s done: %d account(s) processed",
                    cycleId,
                    processed,
                )
            }
            discoveryEnabled -> runDiscoveredSweep(cycleId)
            else -> log.debug("[billing-cycle-scheduler] No accounts configured, discovery off — skipping sweep")
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
                    processed += billingCycleService.runCycle(cycleId, page.accountIds, currency)
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
        /** Matches account-service's own default page size for the /accounts/active sweep. */
        const val DEFAULT_DISCOVERY_PAGE_SIZE = 100

        private val CYCLE_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

        /** The billing cycle id for a date: the calendar month, e.g. `2026-07`. */
        fun cycleIdFor(date: LocalDate): String = date.format(CYCLE_ID_FORMAT)
    }
}
