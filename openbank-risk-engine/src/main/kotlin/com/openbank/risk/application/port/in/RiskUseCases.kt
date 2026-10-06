// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.port.`in`

import com.openbank.risk.application.port.out.CurveSetSummary
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.domain.capital.CapitalParameters
import com.openbank.risk.domain.capital.CapitalResult
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.SnapshotCashFlows
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.MoneyMarketQuote
import com.openbank.risk.domain.irrbb.IrrbbParameters
import com.openbank.risk.domain.irrbb.IrrbbReportingAggregate
import com.openbank.risk.domain.irrbb.IrrbbResult
import com.openbank.risk.domain.limits.LimitDefinition
import com.openbank.risk.domain.limits.LimitEvaluation
import com.openbank.risk.domain.limits.LimitSet
import com.openbank.risk.domain.limits.MetricInput
import com.openbank.risk.domain.liquidity.LiquidityForecastResult
import com.openbank.risk.domain.liquidity.LiquidityParameters
import com.openbank.risk.domain.liquidity.LiquidityResult
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.reserves.MaintenanceCalendar
import com.openbank.risk.domain.reserves.MinReserveParameters
import com.openbank.risk.domain.reserves.MinReserveResult
import com.openbank.risk.domain.reserves.PeriodAveraging
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Result of a snapshot request: the run, and whether it already existed (a replay). */
data class SnapshotOutcome(val run: SnapshotRun, val replayed: Boolean)

interface SnapshotUseCase {
    suspend fun listRuns(limit: Int): List<SnapshotRunSummary>

    /** One TIED_OUT run per as-of date in [from]..[to], the latest recorded; oldest date first. */
    suspend fun listTiedOutBetween(from: LocalDate, to: LocalDate): List<SnapshotRunSummary>

    /** [requestedBy] is the caller's principal name; a replay keeps the first run's requester. */
    suspend fun createSnapshot(asOf: LocalDate, requestedBy: String? = null): SnapshotOutcome

    suspend fun getRun(id: UUID): SnapshotRun

    /** Throws [UntiedSnapshotException] for a run that did not tie out (ADR-0314 D3). */
    suspend fun getPositions(id: UUID): List<Position>

    /** The run's contract-level instruments (ADR-0314 D4); same 404 / 409 rules as [getPositions]. */
    suspend fun getInstruments(id: UUID): List<Instrument>
}

/** An operator's curve upload: simple money-market quotes per index. */
data class CreateCurveSetCommand(
    val asOf: LocalDate,
    val provenance: Provenance,
    val source: String,
    val quotes: Map<CurveIndex, List<MoneyMarketQuote>>,
)

interface CurveSetUseCase {
    /** Newest first; only the sets as of [asOf] when given (a run's reads need its own date). */
    suspend fun list(limit: Int, asOf: LocalDate? = null): List<CurveSetSummary>

    suspend fun create(command: CreateCurveSetCommand): CurveSet

    suspend fun get(id: UUID): CurveSet

    /** The newest set recorded as of exactly [asOf], or null; a neighbouring day's set is never offered. */
    suspend fun latestIdFor(asOf: LocalDate): UUID?
}

/** A run's flows, with the run and the curve set they were derived from. */
data class CashFlowProjection(val run: SnapshotRun, val curveSet: CurveSet, val flows: SnapshotCashFlows)

interface CashFlowUseCase {
    /** Throws [com.openbank.risk.application.port.out.UntiedSnapshotException] for an UNTIED run. */
    suspend fun project(runId: UUID, curveSetId: UUID): CashFlowProjection
}

/** An IRRBB analysis of a run under a curve set (ADR-0313 phase 1). */
data class IrrbbAnalysis(
    val run: SnapshotRun,
    val curveSet: CurveSet,
    val model: BehaviouralModel,
    val parameters: IrrbbParameters,
    val result: IrrbbResult,
    /** Operator-supplied Tier 1 capital, in the aggregation currency; null when not supplied. */
    val tier1Capital: BigDecimal?,
    /** The multi-currency book's CZK aggregate at ČNB fixings; null for a single-currency book. */
    val reporting: IrrbbReportingAggregate? = null,
    /**
     * Tier 1 from the run's own-funds lines (CZK) — the same figure the `irrbb-eve-outlier` limit
     * uses — or the gap that prevents it. Used only when the caller supplied none.
     */
    val ownFundsTier1: MetricInput? = null,
    /** The declared `irrbb-eve-outlier` limit, when the active limit set has one. */
    val outlierLimit: LimitDefinition? = null,
)

interface IrrbbUseCase {
    /**
     * Same 404 / 409 / 400 rules as [CashFlowUseCase.project]. A null [curveSetId] means the newest
     * set recorded as of the run's own date; 400 when there is none.
     */
    suspend fun analyse(runId: UUID, curveSetId: UUID?, tier1Capital: BigDecimal?): IrrbbAnalysis
}

/** LCR and NSFR of a run under a versioned parameter set (ADR-0313 phase 1). */
data class LiquidityAnalysis(val run: SnapshotRun, val parameters: LiquidityParameters, val result: LiquidityResult)

interface LiquidityUseCase {
    /** 404 for an unknown run, 409 ([com.openbank.risk.application.port.out.UntiedSnapshotException]) for an UNTIED one. */
    suspend fun analyse(runId: UUID): LiquidityAnalysis
}

/** Pillar 1 credit-risk RWA and capital ratios of a run under a versioned parameter set (ADR-0313 phase 2). */
data class CapitalAnalysis(val run: SnapshotRun, val parameters: CapitalParameters, val result: CapitalResult)

interface CapitalUseCase {
    /** 404 for an unknown run, 409 ([com.openbank.risk.application.port.out.UntiedSnapshotException]) for an UNTIED one. */
    suspend fun analyse(runId: UUID): CapitalAnalysis
}

/** A liquidity survival forecast of a run under a curve set (ADR-0313 "forecasting"). */
data class LiquidityForecastAnalysis(
    val run: SnapshotRun,
    val curveSet: CurveSet,
    val model: BehaviouralModel,
    val parameters: LiquidityParameters,
    val result: LiquidityForecastResult,
)

interface LiquidityForecastUseCase {
    /** Same 404 / 409 / 400 rules as [CashFlowUseCase.project]; a horizon outside 1..365 is a 400. */
    suspend fun forecast(runId: UUID, curveSetId: UUID, horizonDays: Int): LiquidityForecastAnalysis
}

/** ČNB minimum reserve requirement of a run under a versioned parameter set (ADR-0313, ADR-0315). */
data class MinReservesAnalysis(
    val run: SnapshotRun,
    val parameters: MinReserveParameters,
    val result: MinReserveResult,
)

/**
 * The minimum reserve requirement of run [runId] cannot be evaluated: no ČNB reserve ratio or
 * remuneration fact is in effect on [asOf]. Never answered with a default rate.
 */
class MinReservesNotEvaluableException(val runId: UUID, val asOf: LocalDate, val reason: String) :
    RuntimeException(reason)

/** One maintenance period's averaging under a versioned calendar and parameter set (ADR-0315 D8). */
data class MinReservesPeriodAnalysis(
    val calendar: MaintenanceCalendar,
    val parameters: MinReserveParameters,
    /** The TIED_OUT run whose base sets the requirement; null when there is none on the reference date. */
    val baseRunId: UUID?,
    val averaging: PeriodAveraging,
)

interface MinReservesPeriodUseCase {
    fun calendar(): MaintenanceCalendar

    /** [asOf] is the evaluation date (default: today); unknown period → [MaintenancePeriodNotFoundException]. */
    suspend fun analysePeriod(periodId: String, asOf: LocalDate?): MinReservesPeriodAnalysis
}

class MaintenancePeriodNotFoundException(periodId: String) :
    RuntimeException("maintenance period '$periodId' is not in the configured calendar")

interface MinReservesUseCase {
    /** 404 for an unknown run, 409 ([com.openbank.risk.application.port.out.UntiedSnapshotException]) for an UNTIED one. */
    suspend fun analyse(runId: UUID): MinReservesAnalysis
}

/**
 * The declarative risk limits (ADR-0313 D9) evaluated on one run: derived on request from the same
 * reads as /liquidity, /capital and /irrbb, never stored. [curveSetId] is the curve set the IRRBB
 * limit was priced on (the latest one recorded for the run's as-of date), or null when none exists.
 */
data class LimitAnalysis(
    val run: SnapshotRun,
    val set: LimitSet,
    val evaluations: List<LimitEvaluation>,
    val curveSetId: UUID?,
)

interface LimitUseCase {
    /** 404 for an unknown run, 409 ([com.openbank.risk.application.port.out.UntiedSnapshotException]) for an UNTIED one. */
    suspend fun evaluate(runId: UUID): LimitAnalysis
}
