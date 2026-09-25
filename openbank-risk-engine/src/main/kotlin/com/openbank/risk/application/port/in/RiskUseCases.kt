// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.port.`in`

import com.openbank.risk.application.port.out.CurveSetSummary
import com.openbank.risk.application.port.out.SnapshotRunSummary
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.SnapshotCashFlows
import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.MoneyMarketQuote
import com.openbank.risk.domain.irrbb.IrrbbParameters
import com.openbank.risk.domain.irrbb.IrrbbResult
import com.openbank.risk.domain.liquidity.LiquidityParameters
import com.openbank.risk.domain.liquidity.LiquidityResult
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.Provenance
import com.openbank.risk.domain.model.SnapshotRun
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Result of a snapshot request: the run, and whether it already existed (a replay). */
data class SnapshotOutcome(val run: SnapshotRun, val replayed: Boolean)

interface SnapshotUseCase {
    suspend fun listRuns(limit: Int): List<SnapshotRunSummary>

    suspend fun createSnapshot(asOf: LocalDate): SnapshotOutcome

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
    suspend fun list(limit: Int): List<CurveSetSummary>

    suspend fun create(command: CreateCurveSetCommand): CurveSet

    suspend fun get(id: UUID): CurveSet
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
    /** Operator-supplied Tier 1 capital, in the aggregation currency; never fetched or defaulted. */
    val tier1Capital: BigDecimal?,
)

interface IrrbbUseCase {
    /** Same 404 / 409 / 400 rules as [CashFlowUseCase.project]. */
    suspend fun analyse(runId: UUID, curveSetId: UUID, tier1Capital: BigDecimal?): IrrbbAnalysis
}

/** LCR and NSFR of a run under a versioned parameter set (ADR-0313 phase 1). */
data class LiquidityAnalysis(val run: SnapshotRun, val parameters: LiquidityParameters, val result: LiquidityResult)

interface LiquidityUseCase {
    /** 404 for an unknown run, 409 ([com.openbank.risk.application.port.out.UntiedSnapshotException]) for an UNTIED one. */
    suspend fun analyse(runId: UUID): LiquidityAnalysis
}
