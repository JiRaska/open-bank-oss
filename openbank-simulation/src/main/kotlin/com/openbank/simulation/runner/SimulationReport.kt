// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.simulation.runner

import com.openbank.simulation.invariants.Violation

/** The outcome of one seed: the violation that aborted it (or null), and how far it ran. */
data class SeedResult(val seed: Long, val violation: Violation?, val stepsRun: Int) {
    val passed: Boolean get() = violation == null
}

/** Aggregate result of a seed sweep. */
data class SimulationReport(val seedsRun: Int, val stepsPerSeed: Int, val results: List<SeedResult>) {
    val violations: List<SeedResult> get() = results.filter { !it.passed }

    val allPassed: Boolean get() = violations.isEmpty()

    /** The lowest-numbered failing seed — the canonical one to replay. */
    val firstViolation: SeedResult? get() = violations.minByOrNull { it.seed }

    fun summary(): String = if (allPassed) {
        "PASS: $seedsRun seeds × $stepsPerSeed steps, all invariants held"
    } else {
        val first = firstViolation!!
        "FAIL: ${violations.size}/$seedsRun seeds violated; first @ seed=${first.seed} " +
            "step=${first.stepsRun} — ${first.violation!!.invariant}: ${first.violation.detail}"
    }
}
