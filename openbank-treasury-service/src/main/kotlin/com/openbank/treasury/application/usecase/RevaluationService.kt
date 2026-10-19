// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.treasury.application.port.out.AccountingDayClosedException
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.application.port.out.LedgerPostingPort
import com.openbank.treasury.application.port.out.RevaluationRecord
import com.openbank.treasury.application.port.out.RevaluationRepository
import com.openbank.treasury.application.port.out.ValuationPort
import com.openbank.treasury.application.port.out.ValuationUnavailableException
import com.openbank.treasury.domain.cashflow.CashFlowKind
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.DayStepStatus
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.DealKind
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.MatrixPostings
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingMatrix
import com.openbank.treasury.domain.model.ProductType
import com.openbank.treasury.domain.reference.Ifrs9Category
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** One deal the revaluation could not measure, and why (a data gap is never posted as a zero). */
data class RevaluationGap(val dealId: UUID, val reason: String)

data class RevaluationRun(
    val asOf: LocalDate,
    val snapshotId: UUID?,
    val revalued: List<RevaluationRecord>,
    val journals: Int,
    val gaps: List<RevaluationGap>,
    val failures: List<Throwable>,
) {
    /** The REVALUATION day step this run makes (ADR-0344 D7): a gap or a failure is FAILED with its reason. */
    fun step(at: java.time.Instant): DayStepRecord {
        val refused = failures.count { it is AccountingDayClosedException }
        val problems = gaps.size + failures.size
        return when {
            revalued.isEmpty() && problems == 0 ->
                DayStepRecord(asOf, DayStep.REVALUATION, DayStepStatus.SKIPPED, 0, "no fair-value deal to revalue", at)
            problems == 0 -> DayStepRecord.of(asOf, DayStep.REVALUATION, revalued.size, 0, at, journals = journals)
            refused == problems -> DayStepRecord.of(
                asOf,
                DayStep.REVALUATION,
                revalued.size,
                problems,
                at,
                refused,
                journals,
            )
            else -> DayStepRecord(
                asOf,
                DayStep.REVALUATION,
                DayStepStatus.FAILED,
                revalued.size,
                (
                    "$problems deal(s) not revalued: " +
                        (gaps.firstOrNull()?.let { "${it.dealId}: ${it.reason}" } ?: failures.first().message.orEmpty())
                    ).take(DayStepRecord.MAX_DETAIL),
                at,
                journals,
                refused,
            )
        }
    }
}

/**
 * ADR-0344 D7/D8 revaluation: the fair value of every FVTPL / FVOCI security and every derivative
 * comes from the risk engine's valuation of a TIED_OUT snapshot (treasury never prices); the change
 * against what was recognised so far posts as the matrix REVALUATION event (FVOCI → the revaluation
 * reserve, FVTPL and derivatives → unrealised P&L). Idempotent per (deal, snapshot date): the ledger
 * key is `treasury:<dealId>:revaluation:<date>` and the record is unique on the same pair.
 *
 * The measured amount is the CLEAN value (accrued interest is posted by the accrual run) less the
 * carrying amount — amortised cost for a security, zero for a derivative, which is carried only at
 * its fair value. A deal the engine did not value, valued as unpriced, or valued in another currency
 * than its own, is a gap: reported, never posted as zero.
 */
@Suppress("LongParameterList")
class RevaluationService(
    private val deals: DealRepository,
    private val cashFlows: CashFlowRepository,
    private val valuation: ValuationPort,
    private val revaluations: RevaluationRepository,
    private val ledger: LedgerPostingPort,
    private val clock: Clock,
    private val bookCategory: suspend (bookId: String?) -> Ifrs9Category = { Ifrs9Category.AMORTISED_COST },
    private val dayCompletion: DayCompletionRepository? = null,
) {
    suspend fun latestAsOf(): LocalDate? = valuation.latestAsOf()

    suspend fun records(asOf: LocalDate, limit: Int): List<RevaluationRecord> {
        require(limit in 1..MAX_LIST) { "limit must be 1..$MAX_LIST" }
        return revaluations.forDate(asOf, limit)
    }

    /**
     * Revalue every candidate deal (or only [dealId]) on [asOf]. A whole-book run records the
     * REVALUATION day step; a single-deal run does not, since it says nothing about the rest.
     */
    suspend fun revalue(asOf: LocalDate, dealId: UUID? = null): RevaluationRun {
        val run = measure(asOf, dealId)
        if (dealId == null) dayCompletion?.record(run.step(clock.instant()))
        return run
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun measure(asOf: LocalDate, dealId: UUID?): RevaluationRun {
        val candidates = candidates(dealId)
        if (candidates.isEmpty()) return RevaluationRun(asOf, null, emptyList(), 0, emptyList(), emptyList())
        val book = try {
            valuation.valuation(asOf)
        } catch (e: ValuationUnavailableException) {
            return RevaluationRun(
                asOf,
                null,
                emptyList(),
                0,
                candidates.map { RevaluationGap(it.first.id, "valuation unavailable: ${e.message}") },
                emptyList(),
            )
        }
            ?: return RevaluationRun(
                asOf,
                null,
                emptyList(),
                0,
                candidates.map { RevaluationGap(it.first.id, "no TIED_OUT risk snapshot as of $asOf") },
                emptyList(),
            )
        val done = mutableListOf<RevaluationRecord>()
        val gaps = mutableListOf<RevaluationGap>()
        val failures = mutableListOf<Throwable>()
        var journals = 0
        candidates.forEach { (deal, category) ->
            runCatching {
                revaluations.find(deal.id, asOf)?.let { prior ->
                    done += prior
                    return@runCatching
                }
                val fv = book.deals[deal.id]
                val gap = when {
                    fv == null -> "not in the risk engine's valuation of snapshot ${book.snapshotId}"
                    fv.unpriced != null -> "unpriced by the risk engine: ${fv.unpriced}"
                    fv.clean == null -> "the risk engine reported no clean value"
                    book.currency != deal.currency -> "valued in ${book.currency}, the deal is in ${deal.currency}"
                    else -> null
                }
                if (gap != null) {
                    gaps += RevaluationGap(deal.id, gap)
                    return@runCatching
                }
                val carrying = carrying(deal, asOf)
                val target = RoundingPolicy.TREASURY_AMOUNT.round(checkNotNull(fv?.clean) - carrying)
                val before = revaluations.cumulativeBefore(deal.id, asOf)
                val change = target - before
                val spec = MatrixPostings.revaluation(deal, change, deal.currency, asOf, category)
                val ref = spec?.let {
                    val posted = ledger.post(it, asOf, "treasury ${deal.product} revaluation $asOf")
                    LedgerJournalRef(
                        deal.id,
                        PostingEvent.REVALUATION,
                        it.idempotencyKey,
                        posted.journalId,
                        clock.instant(),
                        posted.accounts,
                    )
                }
                val record = RevaluationRecord(
                    deal.id,
                    asOf,
                    book.snapshotId,
                    checkNotNull(fv?.clean),
                    carrying,
                    target,
                    change,
                    spec?.idempotencyKey,
                    ref?.journalId,
                    clock.instant(),
                )
                // The loser of a concurrent replay finds the winner's row: same key, same journal.
                if (!revaluations.save(record, ref)) {
                    done += checkNotNull(revaluations.find(deal.id, asOf)) { "revaluation of ${deal.id} vanished" }
                    return@runCatching
                }
                done += record
                if (ref != null) journals++
            }.onFailure { failures += it }
        }
        return RevaluationRun(asOf, book.snapshotId, done, journals, gaps, failures)
    }

    /** Live ACTUAL matrix deals whose product and IFRS 9 category carry a REVALUATION rule. */
    private suspend fun candidates(only: UUID?): List<Pair<Deal, Ifrs9Category>> {
        val live = if (only != null) {
            listOfNotNull(deals.findById(only))
        } else {
            LIVE_STATES.flatMap { deals.list(it) }
        }
        return live.filter { d ->
            d.kind == DealKind.ACTUAL &&
                !d.product.legacy &&
                d.state in LIVE_STATES &&
                (d.product in DERIVATIVES || d.state == DealState.SETTLED)
        }.mapNotNull { d ->
            val category = if (d.product in DERIVATIVES) Ifrs9Category.FVTPL else bookCategory(d.bookId)
            d.takeIf { PostingMatrix.rows(d.product, PostingEvent.REVALUATION, category).isNotEmpty() }
                ?.let { it to category }
        }.take(MAX_DEALS)
    }

    /** Amortised cost of a security (consideration + amortisation so far); a derivative carries none. */
    private suspend fun carrying(deal: Deal, asOf: LocalDate): BigDecimal {
        if (deal.product in DERIVATIVES) return BigDecimal.ZERO
        val flows = cashFlows.flows(deal.id, null)
        val consideration = MatrixPostings.settlementFlows(deal, flows)
            .filter { it.kind != CashFlowKind.INTEREST }
            .sumOf { checkNotNull(it.amount) }
        return consideration + MatrixPostings.amortisedThrough(deal, flows, asOf)
    }

    private companion object {
        const val MAX_LIST = 1_000
        const val MAX_DEALS = 5_000
        val LIVE_STATES = listOf(DealState.BOOKED, DealState.CONFIRMED, DealState.SETTLED)
        val DERIVATIVES = setOf(ProductType.IRS, ProductType.FRA, ProductType.FX_FORWARD, ProductType.FX_SWAP)
    }
}
