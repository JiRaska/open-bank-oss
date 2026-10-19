// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.finance.cashflow.FixingSource
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.DealRepository
import com.openbank.treasury.application.port.out.FixingFlowRepository
import com.openbank.treasury.application.port.out.MarketDataRepository
import com.openbank.treasury.application.port.out.ObservationBatchNotFoundException
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.marketdata.BatchKind
import com.openbank.treasury.domain.marketdata.BatchStatus
import com.openbank.treasury.domain.marketdata.FixingCase
import com.openbank.treasury.domain.marketdata.FixingCaseStatus
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.ObservationBatch
import com.openbank.treasury.domain.marketdata.QualityGate
import com.openbank.treasury.domain.marketdata.QualityThresholds
import com.openbank.treasury.domain.marketdata.QualityVerdict
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.CashFlowFixed
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.Deal
import com.openbank.treasury.domain.model.FixedFlowItem
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

enum class FixingStatus { DUE, FIXED, BLOCKED }

/** One (index, fixing date) as `GET /fixings` shows it. */
data class FixingView(
    val index: String,
    val fixingDate: LocalDate,
    val status: FixingStatus,
    val rate: BigDecimal?,
    val source: String?,
    val dealCount: Int,
    val flowIds: List<UUID>,
    val blockReason: String?,
    val staleDays: Int?,
    val jumpBp: Int?,
    val missing: Boolean,
)

data class FixingRunResult(
    val date: LocalDate,
    val fixedFlows: Int,
    val fixedIndices: List<String>,
    val blocked: List<FixingView>,
    val failures: List<Throwable>,
)

/** `GET /market-data/quality`: what the gate would say today about every index due on [date]. */
data class QualityReport(
    val date: LocalDate,
    val suspicious: List<QualityVerdict>,
    val missing: List<Pair<QualityVerdict, Int>>,
)

/**
 * ADR-0344 D5: the fixing workflow. Due flows are the PROJECTED floating flows whose fixing date —
 * accrual start (in advance) or accrual end (in arrears) minus the index's fixing lag, in business
 * days on its fixing calendar, computed by the schedule engine — is the run date; the payment date
 * and any cut-off play no part. Each (index, date) passes the quality gate first: a blocked one opens
 * a case and fixes nothing. A passed one re-projects the deal's schedule with exactly that observed
 * value through the shared engine and moves each due flow to FIXED with its amount, rate,
 * rateSource OBSERVED, the observation's source and SHA-256, emitting `treasury.cashflow.fixed.v1`
 * in the same transaction.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class FixingService(
    private val flows: FixingFlowRepository,
    private val deals: DealRepository,
    private val schedules: ScheduleService,
    private val marketData: MarketDataRepository,
    private val dayCompletion: DayCompletionRepository?,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val newId: () -> UUID = { Ids.newId() },
) {
    /** The fixings of [date]: what is due, what was fixed, what is blocked. [status] narrows it. */
    suspend fun fixings(date: LocalDate, status: FixingStatus?): List<FixingView> {
        val due = groupByIndex(flows.dueOn(date))
        val cases = marketData.cases(date, FixingCaseStatus.OPEN, MAX_LIST).associateBy { it.index }
        val fixed = groupByIndex(flows.fixedOn(date))
        val views = buildList {
            due.forEach { (index, fs) ->
                val case = cases[index]
                add(view(index, date, if (case != null) FixingStatus.BLOCKED else FixingStatus.DUE, fs, case))
            }
            fixed.forEach { (index, fs) ->
                if (index !in
                    due
                ) {
                    add(view(index, date, FixingStatus.FIXED, fs, null, fs.first().rate, fs.first().fixingSource))
                }
            }
        }
        return views.filter { status == null || it.status == status }.sortedBy { it.index }
    }

    /** Fix every due (index, [date]) that passes the quality gate; block the rest. Records the FIXINGS step. */
    suspend fun run(date: LocalDate): FixingRunResult {
        val byIndex = groupByIndex(flows.dueOn(date))
        var fixedFlows = 0
        val fixedIndices = mutableListOf<String>()
        val blocked = mutableListOf<FixingView>()
        val failures = mutableListOf<Throwable>()
        byIndex.forEach { (index, due) ->
            runCatching {
                val verdict = verdict(index, date)
                if (!verdict.passed) {
                    val case = openCase(verdict)
                    blocked += view(index, date, FixingStatus.BLOCKED, due, case)
                } else {
                    fixedFlows += fix(index, date, verdict, due)
                    fixedIndices += index
                    resolveCase(index, date)
                }
            }.onFailure { failures += it }
        }
        // A blocked fixing holds the day as a failure would: the close must not pass with an
        // unfixed coupon (ADR-0342), so the FIXINGS step is not DONE until the case is resolved.
        dayCompletion?.record(
            DayStepRecord.of(date, DayStep.FIXINGS, fixedFlows, failures.size + blocked.size, clock.instant())
                .let {
                    if (blocked.isNotEmpty() &&
                        failures.isEmpty()
                    ) {
                        it.copy(detail = "${blocked.size} index fixing(s) BLOCKED by the quality gate")
                    } else {
                        it
                    }
                },
        )
        return FixingRunResult(date, fixedFlows, fixedIndices, blocked, failures)
    }

    /** What the gate says about [index] on [date], from ACTIVE observations only. */
    suspend fun verdict(index: String, date: LocalDate): QualityVerdict {
        val (terms, calendar) = schedules.indexTerms(index, date)
        val window = marketData.active(index, date.minusDays(LOOKBACK_DAYS), date)
        return QualityGate.check(
            index,
            date,
            window,
            QualityThresholds(terms.staleAfterBusinessDays, terms.suspiciousJumpBp),
            calendar,
        )
    }

    suspend fun quality(date: LocalDate): QualityReport {
        val due = groupByIndex(flows.dueOn(date))
        val verdicts = due.mapValues { (index, _) -> verdict(index, date) }
        return QualityReport(
            date,
            suspicious = verdicts.values.filter { !it.missing && (it.stale || it.suspicious) },
            missing = verdicts.filter { it.value.missing }.map { (index, v) ->
                v to due.getValue(index).map { it.dealId }.distinct().size
            },
        )
    }

    private suspend fun fix(index: String, date: LocalDate, verdict: QualityVerdict, due: List<CashFlow>): Int {
        val value = checkNotNull(verdict.value)
        val observation = checkNotNull(verdict.observation)
        var count = 0
        due.groupBy { it.dealId }.forEach { (dealId, dealFlows) ->
            val deal = checkNotNull(deals.findById(dealId)) { "deal $dealId of a due flow disappeared" }
            val source = FixingSource { i, d -> if (i == index && d == date) value else null }
            val projected = schedules.generate(deal, source).flows.associateBy { it.slot }
            val at = clock.instant()
            val fixed = dealFlows.map { f ->
                val p = projected[f.slot]
                check(p != null && p.rateSource == RateSource.OBSERVED && p.amount != null && p.rate != null) {
                    "the engine did not observe $index@$date for flow ${f.flowId} of deal $dealId"
                }
                f.copy(
                    state = CashFlowState.FIXED,
                    rate = p.rate,
                    amount = p.amount,
                    rateSource = RateSource.OBSERVED,
                    fixingSource = observation.source.name,
                    fixingSha256 = observation.sha256,
                    fixedAt = at,
                )
            }
            flows.applyFixing(dealId, fixed, event(deal, index, date, observation, fixed))
            count += fixed.size
        }
        return count
    }

    private fun event(deal: Deal, index: String, date: LocalDate, obs: IndexObservation, fixed: List<CashFlow>) =
        DealEvent(
            CashFlowFixed.EVENT_TYPE,
            objectMapper.writeValueAsString(
                CashFlowFixed(
                    dealId = deal.id,
                    dealVersion = deal.version,
                    scheduleVersion = fixed.first().scheduleVersion,
                    index = index,
                    fixingDate = date,
                    value = obs.value,
                    source = obs.source.name,
                    sha256 = obs.sha256,
                    flows = fixed.map {
                        FixedFlowItem(
                            it.flowId,
                            it.legIndex,
                            it.periodIndex,
                            it.kind.name,
                            it.direction.name,
                            it.currency,
                            it.accrualStart,
                            it.accrualEnd,
                            it.paymentDate,
                            checkNotNull(it.rate),
                            checkNotNull(it.amount),
                            it.rateSource.name,
                            it.state.name,
                        )
                    },
                    occurredAt = clock.instant(),
                ),
            ),
        )

    private suspend fun openCase(v: QualityVerdict): FixingCase {
        val case = FixingCase(
            index = v.index,
            fixingDate = v.fixingDate,
            status = FixingCaseStatus.OPEN,
            reason = checkNotNull(v.reason).take(MAX_REASON),
            value = v.observation?.value,
            previous = v.previous,
            staleDays = v.staleDays?.takeIf { it != Int.MAX_VALUE },
            jumpBp = v.jumpBp,
            missing = v.missing,
            openedAt = clock.instant(),
        )
        marketData.saveCase(case)
        return case
    }

    private suspend fun resolveCase(index: String, date: LocalDate) {
        marketData.cases(date, FixingCaseStatus.OPEN, MAX_LIST).firstOrNull { it.index == index }?.let {
            marketData.saveCase(it.copy(status = FixingCaseStatus.RESOLVED, resolvedAt = clock.instant()))
        }
    }

    // ── four-eyes market data ──────────────────────────────────────────────────────────────

    /** A manual fixing: one PENDING observation a different human must approve before any run uses it. */
    suspend fun proposeManual(
        index: String,
        date: LocalDate,
        rate: BigDecimal,
        reason: String,
        source: String,
        actor: Actor,
    ): ObservationBatch {
        require(source.isNotBlank() && source.length <= MAX_SOURCE) {
            "source names where the rate was read (1..$MAX_SOURCE)"
        }
        schedules.indexTerms(index, date) // the index must be effective on that date
        val batch = ObservationBatch.propose(
            newId(),
            BatchKind.MANUAL_FIXING,
            listOf(Triple(index, date, rate)),
            "$reason [source: $source]",
            null,
            actor,
            clock.instant(),
        )
        marketData.saveBatch(batch, null)
        return batch
    }

    suspend fun proposeImport(
        rows: List<Triple<String, LocalDate, BigDecimal>>,
        reason: String,
        fileSha256: String,
        actor: Actor,
    ): ObservationBatch {
        val batch = ObservationBatch.propose(
            newId(),
            BatchKind.IMPORT,
            rows,
            reason,
            fileSha256,
            actor,
            clock.instant(),
        )
        marketData.saveBatch(batch, null)
        return batch
    }

    suspend fun approve(batchId: UUID, actor: Actor, manualFor: Pair<String, LocalDate>? = null): ObservationBatch {
        val batch = load(batchId, manualFor)
        val decided = batch.approve(actor, clock.instant())
        marketData.saveBatch(decided, BatchStatus.PENDING)
        return decided
    }

    suspend fun reject(
        batchId: UUID,
        reason: String,
        actor: Actor,
        manualFor: Pair<String, LocalDate>? = null,
    ): ObservationBatch {
        val batch = load(batchId, manualFor)
        val decided = batch.reject(actor, reason, clock.instant())
        marketData.saveBatch(decided, BatchStatus.PENDING)
        return decided
    }

    /** A manual-fixing path must name its batch: the index and date of its one observation (else 404). */
    private suspend fun load(batchId: UUID, manualFor: Pair<String, LocalDate>?): ObservationBatch {
        val batch = marketData.findBatch(batchId) ?: throw ObservationBatchNotFoundException(batchId)
        if (manualFor != null) {
            val row = batch.rows.single()
            if (batch.kind != BatchKind.MANUAL_FIXING || row.index != manualFor.first || row.date != manualFor.second) {
                throw ObservationBatchNotFoundException(batchId)
            }
        }
        return batch
    }

    suspend fun batches(status: BatchStatus?, limit: Int): List<ObservationBatch> = marketData.batches(status, limit)

    suspend fun history(index: String?, from: LocalDate, to: LocalDate, limit: Int): List<IndexObservation> {
        require(!to.isBefore(from) && java.time.temporal.ChronoUnit.DAYS.between(from, to) <= MAX_HISTORY_DAYS) {
            "the history window is from <= to, at most $MAX_HISTORY_DAYS days"
        }
        return marketData.history(index, from, to, limit)
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private suspend fun groupByIndex(due: List<CashFlow>): Map<String, List<CashFlow>> {
        val cache = mutableMapOf<UUID, Deal?>()
        return due.groupBy { indexOf(it, cache) }
    }

    private suspend fun indexOf(f: CashFlow, cache: MutableMap<UUID, Deal?>): String {
        val deal = cache.getOrPut(f.dealId) { deals.findById(f.dealId) }
            ?: error("deal ${f.dealId} of flow ${f.flowId} not found")
        val leg = deal.legs.getOrNull(f.legIndex) ?: deal.legs.firstOrNull { it.rate.index != null }
        return checkNotNull(leg?.rate?.index) { "flow ${f.flowId} of deal ${f.dealId} is not on a floating leg" }
    }

    private fun view(
        index: String,
        date: LocalDate,
        status: FixingStatus,
        fs: List<CashFlow>,
        case: FixingCase?,
        rate: BigDecimal? = null,
        source: String? = null,
    ) = FixingView(
        index = index,
        fixingDate = date,
        status = status,
        rate = rate,
        source = source,
        dealCount = fs.map { it.dealId }.distinct().size,
        flowIds = fs.map { it.flowId },
        blockReason = case?.reason,
        staleDays = case?.staleDays,
        jumpBp = case?.jumpBp,
        missing = case?.missing ?: false,
    )

    companion object {
        const val MAX_LIST = 500
        const val MAX_REASON = 500
        const val MAX_SOURCE = 64
        const val MAX_HISTORY_DAYS = 3660L
        private const val LOOKBACK_DAYS = 400L
    }
}
