// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.risk.application.port.`in`.CashFlowProjection
import com.openbank.risk.application.port.`in`.CashFlowUseCase
import com.openbank.risk.application.port.`in`.CreateCurveSetCommand
import com.openbank.risk.application.port.`in`.CurveSetUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetNotFoundException
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.curve.CurveBootstrap
import com.openbank.risk.domain.curve.CurveSet
import java.time.Clock
import java.util.UUID

/**
 * Operator-uploaded curve sets (ADR-0313 D4): quotes in, bootstrapped curves stored.
 *
 * The bootstrapped PILLARS are stored alongside the quotes and are what a later read returns, so
 * a curve set means the same numbers tomorrow even if the bootstrap method changes.
 */
class CurveSetService(private val repository: CurveSetRepository, private val clock: Clock) : CurveSetUseCase {

    override suspend fun create(command: CreateCurveSetCommand): CurveSet {
        require(command.source.isNotBlank()) { "field 'source' is required" }
        require(command.quotes.isNotEmpty()) { "at least one curve is required" }
        val curves = command.quotes.mapValues { (index, quotes) ->
            CurveBootstrap.bootstrap(index, command.asOf, quotes)
        }
        val set = CurveSet(
            id = Ids.newId(),
            asOf = command.asOf,
            provenance = command.provenance,
            source = command.source.trim(),
            recordedAt = clock.instant(),
            curves = curves,
        )
        repository.save(set, command.quotes)
        return set
    }

    override suspend fun get(id: UUID): CurveSet = repository.findById(id) ?: throw CurveSetNotFoundException(id)
}

/**
 * Cash flows of a snapshot run, derived on request and never stored (ADR-0314 D6).
 *
 * The curve set must be as of the run's date: discounting a September balance sheet on an August
 * curve is a different question, and answering it silently would be the wrong kind of helpful.
 */
class CashFlowService(
    private val snapshots: SnapshotUseCase,
    private val curveSets: CurveSetUseCase,
    private val model: BehaviouralModel,
) : CashFlowUseCase {

    override suspend fun project(runId: UUID, curveSetId: UUID): CashFlowProjection {
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val run = snapshots.getRun(runId)
        val curveSet = curveSets.get(curveSetId)
        require(curveSet.asOf == run.asOf) {
            "curve set ${curveSet.id} is as of ${curveSet.asOf} but run ${run.id} is as of ${run.asOf}"
        }
        return CashFlowProjection(
            run,
            curveSet,
            SnapshotCashFlowProjection.project(positions, run.asOf, curveSet, model, snapshots.getInstruments(runId)),
        )
    }
}
