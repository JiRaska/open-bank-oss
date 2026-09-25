// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure

import com.openbank.risk.application.port.`in`.CashFlowUseCase
import com.openbank.risk.application.port.`in`.CurveSetUseCase
import com.openbank.risk.application.port.`in`.IrrbbUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.application.port.out.LedgerPort
import com.openbank.risk.application.port.out.LendingPort
import com.openbank.risk.application.port.out.SnapshotRepository
import com.openbank.risk.application.usecase.CashFlowService
import com.openbank.risk.application.usecase.CurveSetService
import com.openbank.risk.application.usecase.IrrbbService
import com.openbank.risk.application.usecase.SnapshotService
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.irrbb.IrrbbParameters
import com.openbank.risk.domain.irrbb.PostShockFloor
import com.openbank.risk.domain.irrbb.ShockSizes
import com.openbank.risk.domain.model.Provenance
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.util.Optional

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

    /**
     * `lendingEnabled` (`openbank.risk.lending.enabled`, ADR-0314 D4): read lending's loan book into
     * every snapshot. Off means Loans Receivable stays a GL-level position (the pre-D4 behaviour) —
     * the switch exists because the deployed edge to lending needs an mTLS listener lending does
     * not serve yet, and a snapshot that fails on every call is worse than one that says, by its
     * positions, that loans were not read. A producer-method parameter, not a field with a Kotlin
     * default, so the configured value is what arrives (configproperty-kotlin-defaults).
     */
    @Produces
    @ApplicationScoped
    fun snapshotUseCase(
        ledger: LedgerPort,
        lending: LendingPort,
        repository: SnapshotRepository,
        clock: Clock,
        @ConfigProperty(name = "openbank.risk.lending.enabled", defaultValue = "true") lendingEnabled: Boolean,
    ): SnapshotUseCase =
        SnapshotService(ledger, repository, clock, Provenance.parse(provenance), lending.takeIf { lendingEnabled })

    @Produces
    @ApplicationScoped
    fun curveSetUseCase(repository: CurveSetRepository, clock: Clock): CurveSetUseCase =
        CurveSetService(repository, clock)

    /** The behavioural model is code, not config: a parameter change is a new model version. */
    @Produces
    @ApplicationScoped
    fun cashFlowUseCase(snapshots: SnapshotUseCase, curveSets: CurveSetUseCase): CashFlowUseCase =
        CashFlowService(snapshots, curveSets, BehaviouralModel.NMD_PHASE0)

    /**
     * IRRBB parameters (ADR-0313 phase 1) — CONFIGURATION, never code defaults:
     *  - `openbank.risk.irrbb.shock-sizes`: `CCY=parallel/short/long` in bp, `;`-separated. A
     *    currency absent here gets no scenarios and is reported "not configured". application.yaml
     *    ships EUR only, from BCBS d368 Annex 2 Table 1. CZK is not in that table; its calibration
     *    must be taken from the EBA supervisory-outlier-test RTS by the operator.
     *  - `openbank.risk.irrbb.shock-source`: the citation reported with every result.
     *  - `openbank.risk.irrbb.post-shock-floor`: `atZeroBp/slopeBpPerYear`. d368 leaves floors to
     *    national supervisors (not above zero); unset means NO floor, and the response says so.
     */
    @Produces
    @ApplicationScoped
    fun irrbbUseCase(
        snapshots: SnapshotUseCase,
        curveSets: CurveSetUseCase,
        @ConfigProperty(name = "openbank.risk.irrbb.shock-sizes") shockSizes: Optional<String>,
        @ConfigProperty(name = "openbank.risk.irrbb.shock-source") shockSource: Optional<String>,
        @ConfigProperty(name = "openbank.risk.irrbb.post-shock-floor") floor: Optional<String>,
        @ConfigProperty(name = "openbank.risk.irrbb.post-shock-floor-source") floorSource: Optional<String>,
    ): IrrbbUseCase = IrrbbService(
        snapshots,
        curveSets,
        BehaviouralModel.NMD_PHASE0,
        IrrbbParameters(
            shockSizes = parseShockSizes(shockSizes.orElse("")),
            shockSource = shockSource.orElse("not configured"),
            floor = floor.map { PostShockFloor.parse(it) }.orElse(null),
            floorSource = floorSource.orElse(NO_FLOOR),
        ),
    )

    companion object {
        const val NO_FLOOR =
            "No post-shock floor configured. BCBS d368 Annex 2 leaves floors to national supervisors " +
                "(not above zero); set openbank.risk.irrbb.post-shock-floor from the applicable text."

        fun parseShockSizes(raw: String): Map<String, ShockSizes> = raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { entry ->
                val (ccy, sizes) = entry.split('=', limit = 2).also {
                    require(it.size == 2) { "shock-sizes entry must be CCY=parallel/short/long: '$entry'" }
                }
                ccy.trim().uppercase() to ShockSizes.parse(sizes)
            }
    }
}
