// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.application.port.`in`.CashFlowUseCase
import com.openbank.risk.application.port.`in`.CurveSetUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.application.usecase.CashFlowService
import com.openbank.risk.application.usecase.CurveSetService
import com.openbank.risk.application.usecase.SnapshotService
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.model.Provenance
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock

/**
 * Wires the framework-free use cases ([SnapshotService], [CurveSetService], [CashFlowService]).
 *
 * `openbank.risk.provenance` defaults to `synthetic` (ADR-0313 D13): a run is labelled
 * production only when an environment says so, never by omission.
 */
@ApplicationScoped
class SnapshotServiceProducer {

    @ConfigProperty(name = "openbank.risk.provenance", defaultValue = "synthetic")
    lateinit var provenance: String

    @Produces
    @ApplicationScoped
    fun snapshotUseCase(ledger: LedgerPort, repository: SnapshotRepository, clock: Clock): SnapshotUseCase =
        SnapshotService(ledger, repository, clock, Provenance.parse(provenance))

    @Produces
    @ApplicationScoped
    fun curveSetUseCase(repository: CurveSetRepository, clock: Clock): CurveSetUseCase =
        CurveSetService(repository, clock)

    /** The behavioural model is code, not config: a parameter change is a new model version. */
    @Produces
    @ApplicationScoped
    fun cashFlowUseCase(snapshots: SnapshotUseCase, curveSets: CurveSetUseCase): CashFlowUseCase =
        CashFlowService(snapshots, curveSets, BehaviouralModel.NMD_PHASE0)
}
