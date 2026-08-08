// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.usecase

import com.openbank.billing.application.port.out.AccountPartyLookupPort
import com.openbank.billing.application.port.out.BillableAccountDiscoveryPort
import com.openbank.billing.application.port.out.BillingAssessmentRepository
import com.openbank.billing.domain.AnnualFeeSummary
import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Builds and publishes the PAD (EU) 2014/92 Art. 5 annual statement of fees (ADR-0248): for one
 * account/calendar year, aggregates every posted (and never-reversed) [com.openbank.billing.domain.AssessedFee]
 * into an [AnnualFeeSummary] and idempotently appends the `billing.annual-fee-summary.ready`
 * outbox row.
 *
 * **`year` is a CALENDAR year in the [AccountingClock.BANK_ZONE] the bank operates in (Europe/Prague, matching the
 * scheduled trigger's own timezone — see `AnnualFeeSummaryScheduler`), not a rolling 12-month
 * window** — `[Jan 1 00:00, next Jan 1 00:00)` of that zone, converted to the `Instant` boundaries
 * the repository query needs.
 *
 * **`interestRate` is always `null` here** — billing-service's domain has no source for a
 * debit/credit interest rate anywhere (see [AnnualFeeSummary]'s own KDoc); this is a documented
 * gap, not an oversight, until a port to interest-service (or wherever the rate the customer was
 * actually charged/paid lives) is built.
 */
@ApplicationScoped
class AnnualFeeSummaryService(
    private val assessments: BillingAssessmentRepository,
    private val partyLookup: AccountPartyLookupPort,
    private val discovery: BillableAccountDiscoveryPort,
    private val clock: Clock,
) {
    private val log = Logger.getLogger(AnnualFeeSummaryService::class.java)

    /**
     * Builds and appends the summary for one account. Fail-closed like every other billing read
     * port ([com.openbank.billing.application.usecase.FeeAssessmentService]): if the account's
     * party cannot be resolved, this returns `null` and appends nothing — never publishes a
     * summary with a fabricated `partyRef`.
     */
    suspend fun publishForAccount(accountId: String, year: Int, currency: String): AnnualFeeSummary? {
        val partyRef = partyLookup.partyIdFor(accountId) ?: run {
            log.warnf(
                "[annual-fee-summary] account %s year %d — party lookup failed, skipping",
                accountId.replace('\n', '_').replace('\r', '_'),
                year,
            )
            return null
        }
        val (from, to) = yearBounds(year)
        val postedFees = assessments.postedFeesForAccount(accountId, from, to)
        val summary = AnnualFeeSummary.aggregate(
            accountId = accountId,
            partyRef = partyRef,
            year = year,
            currency = currency,
            candidateFees = postedFees,
            interestRate = null,
        )
        assessments.appendAnnualFeeSummaryEvent(summary, Instant.now(clock))
        return summary
    }

    /**
     * Runs [publishForAccount] for every account account-service's fleet-wide ACTIVE-account
     * sweep discovers (mirrors [BillingCycleService.runCycle]'s per-account isolation: one bad
     * account is logged and skipped, never aborts the rest of the batch — the annual job is
     * idempotent per (accountId, year), so a partial run is always safely re-runnable).
     */
    suspend fun publishForAllAccounts(year: Int, currency: String, pageSize: Int): AnnualFeeSummaryRunResult {
        var published = 0
        var skipped = 0
        var cursor: String? = null
        do {
            val page = discovery.activeAccounts(pageSize, cursor)
            for (accountId in page.accountIds) {
                val result = runCatching { publishForAccount(accountId, year, currency) }
                    .onFailure { ex ->
                        log.errorf(
                            ex,
                            "[annual-fee-summary] account %s year %d failed — continuing with the rest of the batch",
                            accountId.replace('\n', '_').replace('\r', '_'),
                            year,
                        )
                    }
                    .getOrNull()
                if (result != null) published++ else skipped++
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return AnnualFeeSummaryRunResult(published, skipped)
    }

    private fun yearBounds(year: Int): Pair<Instant, Instant> {
        val from = LocalDate.of(year, 1, 1).atStartOfDay(AccountingClock.BANK_ZONE).toInstant()
        val to = LocalDate.of(year + 1, 1, 1).atStartOfDay(AccountingClock.BANK_ZONE).toInstant()
        return from to to
    }
}

/** Outcome of one [AnnualFeeSummaryService.publishForAllAccounts] run. */
data class AnnualFeeSummaryRunResult(val published: Int, val skipped: Int)
