// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.AllocateYearEndResultCommand
import com.openbank.ledger.application.port.`in`.JournalLineRequest
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.PreviewYearEndResultQuery
import com.openbank.ledger.application.port.`in`.YearEndResultAllocationResult
import com.openbank.ledger.application.port.`in`.YearEndResultAllocationUseCase
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseStatus
import com.openbank.ledger.domain.model.YearEndResultAllocationPlan
import com.openbank.ledger.domain.model.requireValid
import com.openbank.libs.domain.calendar.AccountingClock
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Year
import java.util.UUID

/**
 * Year-end result allocation (ADR-0096 D3, first half): posts the closing journal that zeroes the
 * fiscal year's income and expense accounts into retained earnings (6020), dated the last day of
 * that year, through the ordinary posting path — so the day, period and fiscal-year locks all
 * apply to it exactly as to any other journal.
 *
 * Ordering it enforces: the year must have ended (accounting date, ADR-0207) and its year close must
 * NOT be ATTESTED. Allocation happens before the attestation, so the attested trial balance is the
 * allocated one and the attest step's fresh-hash re-verification (four-eyes, #869) is where a
 * second person reviews the closing journal. A DRAFT computed before the allocation goes stale and
 * must be refreshed — the existing drift check already refuses to attest it.
 *
 * Carry-relative: the plan is computed from the year's own trial balance and the posted journal is
 * inside that year, so a re-run sees an empty plan and posts nothing, and a late posting leaves a
 * residual that a re-run allocates. The key is the plan digest plus the first unused ordinal (see
 * [nextKey]); a replayed key is refused, and the post-condition re-plans and requires empty.
 * The allocating principal is recorded on the year-close DRAFT (`allocatedBy`), and attestation
 * refuses that principal.
 *
 * Out of scope, and refused rather than approximated: a non-CZK income/expense balance (see
 * [YearEndResultAllocationPlan.plan]) and the opening-balance roll into the next year.
 */
@ApplicationScoped
class YearEndResultAllocationService(
    private val ledger: LedgerUseCase,
    private val journalRepository: JournalRepository,
    private val glAccounts: GlAccountRepository,
    private val yearCloseRepository: YearCloseRepository,
    private val accountingClock: AccountingClock,
    private val clock: Clock,
) : YearEndResultAllocationUseCase {

    override suspend fun preview(query: PreviewYearEndResultQuery): YearEndResultAllocationPlan = plan(query.fiscalYear)

    override suspend fun allocate(command: AllocateYearEndResultCommand): YearEndResultAllocationResult {
        val fiscalYear = command.fiscalYear
        if (fiscalYear >= accountingClock.today().year) {
            throw YearCloseConflictException(
                "Fiscal year $fiscalYear has not ended yet — its result cannot be allocated",
            )
        }
        requireDraft(fiscalYear)
        val plan = plan(fiscalYear)
        if (plan.isEmpty) {
            log.infof("Year-end result allocation %d: nothing to allocate", fiscalYear)
            return YearEndResultAllocationResult(plan, posted = false, journalId = null)
        }

        val idempotencyKey = nextKey(fiscalYear, plan)
        val yearEnd = Year.of(fiscalYear).let { it.atDay(it.length()) }
        val lines = closingLines(plan)
        val outcome = ledger.postJournalWithOutcome(
            PostJournalCommand(
                idempotencyKey = idempotencyKey,
                transactionId = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray()),
                entryDate = yearEnd,
                valueDate = yearEnd,
                description = "Year-end result allocation $fiscalYear to retained earnings " +
                    "(requested by ${command.requestedBy})",
                lines = lines,
                postedBy = SYSTEM_USER,
            ),
        )
        // A replay while the plan is non-empty means a concurrent run took this key: nothing new
        // was booked by THIS call, so reporting posted=true would be a success that changed nothing.
        if (outcome.replayed) {
            throw YearCloseConflictException(
                "Result allocation $fiscalYear key $idempotencyKey was already posted by a concurrent run — " +
                    "re-read the preview and retry",
            )
        }
        val entry = outcome.entry
        yearCloseRepository.recordAllocation(fiscalYear, command.requestedBy, clock.instant(), entry.id)
            ?: throw YearCloseConflictException(
                "Year close $fiscalYear left DRAFT while the allocation posted (journal ${entry.id}) — " +
                    "the allocation is not attributable; reverse it",
            )
        requireNothingLeft(fiscalYear, entry.id)
        log.infof(
            "Year-end result allocation %d posted as %s under key %s: net result %s over %d leg(s), requested by %s",
            fiscalYear,
            entry.id,
            idempotencyKey,
            plan.netResult.toPlainString(),
            plan.legs.size,
            command.requestedBy,
        )
        return YearEndResultAllocationResult(plan, posted = true, journalId = entry.id)
    }

    private suspend fun requireNothingLeft(fiscalYear: Int, journalId: UUID) {
        // Post-condition: the closing journal must leave nothing to allocate.
        val residual = plan(fiscalYear)
        if (!residual.isEmpty) {
            throw YearCloseConflictException(
                "Result allocation $fiscalYear posted journal $journalId but ${residual.legs.size} leg(s) remain " +
                    "(a concurrent posting landed) — re-run the allocation for the residual",
            )
        }
    }

    private suspend fun requireDraft(fiscalYear: Int) {
        // The allocation is a maker action ON the year close, recorded on its DRAFT so the attest
        // step can refuse the allocator (four-eyes). No DRAFT, no allocation.
        val close = yearCloseRepository.findByFiscalYear(fiscalYear)
        if (close?.status != YearCloseStatus.DRAFT) {
            throw YearCloseConflictException(
                if (close == null) {
                    "Year close $fiscalYear has no DRAFT — create the draft before allocating the result"
                } else {
                    "Year close $fiscalYear is ${close.status} — the result must be allocated before attestation"
                },
            )
        }
    }

    private suspend fun closingLines(plan: YearEndResultAllocationPlan): List<JournalLineRequest> =
        plan.legs.map { leg ->
            val account = glAccounts.findByCode(leg.glAccountCode)
                ?: throw LedgerConflictException("GL account ${leg.glAccountCode} missing for result allocation")
            JournalLineRequest(
                glAccountId = account.id,
                side = leg.side,
                amount = leg.amount,
                currencyCode = YearEndResultAllocationPlan.RESULT_CURRENCY,
                fxRate = null,
                baseAmount = leg.amount,
                baseCurrencyCode = YearEndResultAllocationPlan.RESULT_CURRENCY,
            )
        }

    /**
     * `year-end-result:{year}:{digest}:{n}` with n the first ordinal not yet used. The digest alone
     * can recur (a later residual identical to an earlier one) and would then replay the old journal;
     * the ordinal makes every allocation a distinct key while two concurrent runs over the same state
     * still race for the same one, and the loser is refused as a replay.
     */
    private suspend fun nextKey(fiscalYear: Int, plan: YearEndResultAllocationPlan): String {
        val prefix = "year-end-result:$fiscalYear:${plan.digest()}"
        var n = 0
        while (journalRepository.findByIdempotencyKey("$prefix:$n") != null) n++
        return "$prefix:$n"
    }

    private suspend fun plan(fiscalYear: Int): YearEndResultAllocationPlan {
        requireValid(fiscalYear in YearCloseRecord.MIN_FISCAL_YEAR..YearCloseRecord.MAX_FISCAL_YEAR) {
            "fiscalYear out of range: $fiscalYear"
        }
        val year = Year.of(fiscalYear)
        // Real-only (the port default): the allocation closes the statutory population that the
        // year close attests; synthetic canary activity is not part of it (ADR-0252).
        val lines = journalRepository.trialBalanceForPeriod(year.atDay(1), year.atDay(year.length()))
        return YearEndResultAllocationPlan.plan(fiscalYear, lines, RETAINED_EARNINGS_CODE)
    }

    companion object {
        /** Retained Earnings, CZK equity (V25) — the account own-funds reporting reads. */
        const val RETAINED_EARNINGS_CODE = "6020"

        /** Same system poster identity as the other ledger-originated postings. */
        val SYSTEM_USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000005900")
        private val log: Logger = Logger.getLogger(YearEndResultAllocationService::class.java)
    }
}
