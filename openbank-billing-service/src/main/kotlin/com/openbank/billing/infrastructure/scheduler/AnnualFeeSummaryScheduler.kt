// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.infrastructure.scheduler

import com.openbank.billing.application.usecase.AnnualFeeSummaryService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Clock
import java.time.LocalDate

/**
 * Drives the PAD Art. 5 annual statement-of-fees trigger (ADR-0248) — fires once a year, well
 * after the calendar year it covers has closed, and publishes the PRIOR year's summary for every
 * fleet-wide ACTIVE account (via [AnnualFeeSummaryService.publishForAllAccounts]).
 *
 * **Disabled by default** ([enabled], mirrors `openbank.statement.scheduled-close.enabled=false`
 * in `openbank-statement-service`'s `PeriodCloseScheduler`). This is deliberate and not
 * boilerplate caution: unlike the monthly billing-cycle sweep (which is naturally idempotent and
 * cheap to re-run), an ANNUAL job firing by accident in a dev/test/staging environment would
 * publish a PAD Art. 5 "annual statement of fees ready" event — a real regulatory-document trigger
 * — for every active account in that environment, and there is no cheap way to "un-send" a push
 * duty once document-service's consumer has acted on it. The scheduled trigger existing at all
 * must never be sufficient to fire it; an operator has to explicitly opt this environment in.
 *
 * `suspend fun`, never `runBlocking` (CLAUDE.md's fleet-wide `@Scheduled` rule, #2148/#2187): the
 * work below reaches [AnnualFeeSummaryService.publishForAllAccounts], which calls reactive
 * Panache through `BillingAssessmentRepository` — a plain (non-suspend) `@Scheduled` method
 * carries no Vert.x context and `runBlocking { ... }` around that call throws `HR000068`.
 */
@ApplicationScoped
class AnnualFeeSummaryScheduler(
    private val annualFeeSummaryService: AnnualFeeSummaryService,
    private val clock: Clock,
    @ConfigProperty(name = "openbank.billing.annual-fee-summary.scheduler.enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.billing.annual-fee-summary.scheduler.currency", defaultValue = "CZK")
    private val currency: String,
    @ConfigProperty(name = "openbank.billing.annual-fee-summary.scheduler.discovery-page-size", defaultValue = "100")
    private val discoveryPageSize: Int,
) {
    private val log = Logger.getLogger(AnnualFeeSummaryScheduler::class.java)

    // Europe/Prague explicit (matches PeriodCloseScheduler's own rule, #1302): an unset
    // @Scheduled timeZone means JVM-default, and "which calendar year just closed" must be
    // decided on the bank's own clock, not the pod's.
    @Scheduled(
        cron = "{openbank.billing.annual-fee-summary.scheduler.cron}",
        timeZone = "Europe/Prague",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun runAnnualFeeSummary() {
        if (!enabled) {
            log.debug(
                "[annual-fee-summary-scheduler] Disabled — skipping " +
                    "(openbank.billing.annual-fee-summary.scheduler.enabled=false)",
            )
            return
        }
        // Fires early in year Y+1 and covers year Y — the calendar year that has actually closed.
        val year = LocalDate.now(clock).minusYears(1).year
        log.infof("[annual-fee-summary-scheduler] Starting annual fee-summary publish for year %d", year)
        val result = annualFeeSummaryService.publishForAllAccounts(year, currency, discoveryPageSize)
        log.infof(
            "[annual-fee-summary-scheduler] year %d done: %d published, %d skipped",
            year,
            result.published,
            result.skipped,
        )
    }
}
