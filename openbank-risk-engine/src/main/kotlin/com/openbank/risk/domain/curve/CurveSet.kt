// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import com.openbank.risk.domain.model.Provenance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A named set of curves as of one date, the unit a run references (ADR-0314 D2: the manifest
 * stores the curve-set id). [provenance] says whether the quotes were real market data or made
 * up for the sandbox (ADR-0313 D13); [source] says where they came from in words.
 */
data class CurveSet(
    val id: UUID,
    val asOf: LocalDate,
    val provenance: Provenance,
    val source: String,
    val recordedAt: Instant,
    val curves: Map<CurveIndex, Curve>,
) {
    init {
        require(curves.isNotEmpty()) { "a curve set needs at least one curve" }
        curves.forEach { (index, curve) ->
            require(curve.index == index) { "curve for ${curve.index} filed under $index" }
            require(curve.asOf == asOf) { "curve ${index.name} is as of ${curve.asOf}, the set as of $asOf" }
        }
    }

    /** The discounting curve for [currency], or null — which the caller must surface as unpriced. */
    fun discountCurveFor(currency: String): Curve? = CurveIndex.discountingFor(currency)?.let { curves[it] }
}
