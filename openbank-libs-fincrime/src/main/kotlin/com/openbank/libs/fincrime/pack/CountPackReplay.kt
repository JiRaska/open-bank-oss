// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime.pack

import java.time.Instant
import java.util.Collections

data class CountReplayCase(val id: String, val at: Instant, val observations: List<CountObservation>)

data class CountReplayDifference(
    val caseId: String,
    val baselinePack: PackRef,
    val candidatePack: PackRef,
    val baseline: CountEvaluation,
    val candidate: CountEvaluation,
    val behaviorChanged: Boolean,
)

/** Same evaluator and explicit snapshots for both sides; no learning, signing or promotion. */
object CountPackReplay {
    const val MAX_CASES: Int = 256

    fun compare(
        baseline: CompiledCountPack,
        candidate: CompiledCountPack,
        cases: List<CountReplayCase>,
    ): List<CountReplayDifference> {
        require(
            baseline.domain == candidate.domain &&
                baseline.purpose == candidate.purpose &&
                baseline.reference.id == candidate.reference.id,
        ) { "replay must compare the same pack and domain purpose" }
        val snapshot = cases.take(MAX_CASES + 1)
        require(snapshot.size in 1..MAX_CASES) { "replay must evaluate 1..$MAX_CASES cases" }
        require(snapshot.map { it.id }.distinct().size == snapshot.size) { "duplicate replay case" }
        val differences = snapshot.map {
            packIdentifier(it.id)
            val inputs = it.observations.take(CompiledCountPack.MAX_RULES + 1)
            val before = baseline.evaluate(baseline.reference, it.at, inputs)
            val after = candidate.evaluate(candidate.reference, it.at, inputs)
            CountReplayDifference(it.id, baseline.reference, candidate.reference, before, after, changed(before, after))
        }
        return Collections.unmodifiableList(differences)
    }

    private fun changed(before: CountEvaluation, after: CountEvaluation): Boolean = when {
        before is CountEvaluation.Evaluated && after is CountEvaluation.Evaluated -> before.findings != after.findings
        else -> before != after
    }
}
