// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.domain.curve.ReferenceCurveSet
import com.openbank.risk.domain.model.Provenance
import java.time.Clock
import java.time.LocalDate

/** How one seeding pass went; two distinct counts, never a shared success flag. */
data class ReferenceSeedOutcome(val created: List<LocalDate>, val alreadyPresent: Int)

/**
 * Gives every recent snapshot as-of date the sandbox [ReferenceCurveSet], so IRRBB, cash flows and
 * the liquidity forecast — which all need a curve set as of the run's own date — have one to use.
 *
 * Refuses outright when the engine runs with `production` provenance: demo quotes next to real
 * balance-sheet data would produce figures that look real and are not (ADR-0313 D13).
 */
class ReferenceCurveSetSeeder(
    private val repository: CurveSetRepository,
    private val snapshots: SnapshotUseCase,
    private val engineProvenance: Provenance,
    private val clock: Clock,
) {

    suspend fun seedRecentRunDates(runLookback: Int = RUN_LOOKBACK): ReferenceSeedOutcome {
        check(engineProvenance == Provenance.SYNTHETIC) {
            "reference curve sets are demo data and are never seeded where openbank.risk.provenance is production"
        }
        val dates = snapshots.listRuns(runLookback).map { it.asOf }.distinct().sorted()
        val created = mutableListOf<LocalDate>()
        var present = 0
        for (asOf in dates) {
            val set = ReferenceCurveSet.build(asOf, clock.instant())
            if (repository.saveIfAbsent(set, ReferenceCurveSet.QUOTES)) created += asOf else present++
        }
        return ReferenceSeedOutcome(created, present)
    }

    companion object {
        /** The runs list endpoint's own upper bound; older runs keep whatever set they already have. */
        const val RUN_LOOKBACK = 100
    }
}
