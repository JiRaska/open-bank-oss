// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.openbank.billing.application.usecase.BillingCycleService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Monthly billing cycle sweep (ADR-0143 phase 2c). Derives a stable `cycleId` from the current
 * month (`yyyy-MM`, e.g. `"2026-07"`) using an injected [Clock] — **never** `Instant.now()`
 * directly (ADR-0100 DST rule, CI-enforced) — and delegates to [BillingCycleService.runCycle] for
 * every configured account.
 *
 * **Known limitation, honestly scoped**: there is no fleet-wide "list every billable account"
 * read port yet (account-service's `listAccounts` is scoped to a single `partyId`, and a
 * monthly-turnover-style aggregate read port does not exist either — ADR-0143's own "Negative"
 * consequences section flags this). Until that port lands, the account batch this scheduler
 * assesses is **operator-configured** (`openbank.billing.scheduler.account-ids`, a comma-separated
 * list) rather than autonomously discovered — the same honesty this codebase already applies to
 * `InterestService.accrueAll`/`capitalizeAll` (both are documented stubs pending "fetch all active
 * accounts"). An empty list is a safe no-op default so this scheduler cannot charge anyone by
 * accident before an operator wires real account discovery (a follow-up, tracked in the PR).
 */
@ApplicationScoped
class BillingCycleScheduler {

    @Inject
    lateinit var billingCycleService: BillingCycleService

    @Inject
    lateinit var clock: Clock

    @ConfigProperty(name = "openbank.billing.scheduler.enabled", defaultValue = "false")
    var enabled: Boolean = false

    @ConfigProperty(name = "openbank.billing.scheduler.account-ids", defaultValue = "")
    lateinit var accountIdsCsv: String

    @ConfigProperty(name = "openbank.billing.scheduler.currency", defaultValue = "CZK")
    lateinit var currency: String

    private val log = Logger.getLogger(BillingCycleScheduler::class.java)

    @Scheduled(
        cron = "{openbank.billing.scheduler.cron}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun sweep(): Unit = runBlocking { runSweep() }

    /** The cycle-sweep logic, split out from the `@Scheduled` entrypoint so ITs can drive it directly. */
    suspend fun runSweep() {
        if (!enabled) {
            log.debug("[billing-cycle-scheduler] Disabled — skipping sweep")
            return
        }
        val accountIds = accountIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (accountIds.isEmpty()) {
            log.debug("[billing-cycle-scheduler] No accounts configured — skipping sweep")
            return
        }
        val cycleId = cycleIdFor(LocalDate.now(clock))
        log.infof(
            "[billing-cycle-scheduler] Starting billing cycle %s for %d configured account(s)",
            cycleId,
            accountIds.size,
        )
        val processed = billingCycleService.runCycle(cycleId, accountIds, currency)
        log.infof("[billing-cycle-scheduler] Billing cycle %s done: %d account(s) processed", cycleId, processed)
    }

    companion object {
        private val CYCLE_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

        /** The billing cycle id for a date: the calendar month, e.g. `2026-07`. */
        fun cycleIdFor(date: LocalDate): String = date.format(CYCLE_ID_FORMAT)
    }
}
