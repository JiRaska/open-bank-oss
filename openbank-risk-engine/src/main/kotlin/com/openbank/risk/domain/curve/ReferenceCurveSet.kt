// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import com.openbank.risk.domain.model.Provenance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The sandbox's reference curve set: ONE fixed table of illustrative money-market quotes for all
 * six indices, stamped onto whatever as-of date a snapshot run needs (ADR-0313 D4, D13).
 *
 * **These are demo values, not market data, and everything that carries them says so:** the set's
 * provenance is always [Provenance.SYNTHETIC] and its [SOURCE] text names it as illustrative. The
 * levels are chosen to look like a plausible CZK/EUR money market (a mildly inverted short end) so
 * IRRBB and the liquidity forecast produce readable figures, but they are NOT a fixing of any date
 * and must never be read as one. A real market feed (ČNB PRIBOR/CZEONIA, ECB €STR) would be a
 * separate, `production`-provenance supply.
 *
 * Why a set per as-of date rather than one set: every read requires the curve set to be as of the
 * run's own date (IRRBB, cash flows and the liquidity forecast all refuse otherwise), so a single
 * dated set would only ever match one run.
 *
 * Tenors stop at 1Y because [CurveBootstrap] builds only from money-market deposits; the curve
 * extrapolates flat beyond its last pillar — a stated phase-0 limit of the curve library.
 *
 * The id is name-based on the as-of date ([idFor]), so a second write for the same date collides on
 * the primary key and is a no-op: idempotent across ticks, restarts and concurrent replicas
 * without a schema change.
 */
object ReferenceCurveSet {

    /** Bumping the table means a new version here, and a new id namespace in [idFor]. */
    const val VERSION = "v1"

    const val SOURCE =
        "OpenBank sandbox reference curves $VERSION - illustrative demo quotes, NOT market data"

    private fun quotes(vararg pairs: Pair<String, String>): List<MoneyMarketQuote> =
        pairs.map { (tenor, rate) -> MoneyMarketQuote(Tenor.parse(tenor), BigDecimal(rate)) }

    /** Simple money-market rates as fractions (0.0347 = 3.47 %). Illustrative only. */
    val QUOTES: Map<CurveIndex, List<MoneyMarketQuote>> = mapOf(
        CurveIndex.CZEONIA to quotes(
            "ON" to "0.0347",
            "1W" to "0.0347",
            "1M" to "0.0346",
            "3M" to "0.0344",
            "6M" to "0.0340",
            "1Y" to "0.0335",
        ),
        CurveIndex.PRIBOR_1M to quotes("1M" to "0.0355", "3M" to "0.0353", "6M" to "0.0350", "1Y" to "0.0345"),
        CurveIndex.PRIBOR_3M to quotes("1M" to "0.0357", "3M" to "0.0356", "6M" to "0.0353", "1Y" to "0.0348"),
        CurveIndex.PRIBOR_6M to quotes("1M" to "0.0359", "3M" to "0.0358", "6M" to "0.0356", "1Y" to "0.0351"),
        CurveIndex.ESTR to quotes(
            "ON" to "0.0192",
            "1W" to "0.0192",
            "1M" to "0.0191",
            "3M" to "0.0190",
            "6M" to "0.0188",
            "1Y" to "0.0185",
        ),
        CurveIndex.EURIBOR_3M to quotes("1M" to "0.0200", "3M" to "0.0203", "6M" to "0.0206", "1Y" to "0.0210"),
    )

    /** Deterministic per date, so the primary key makes a repeat write a no-op. */
    fun idFor(asOf: LocalDate): UUID =
        UUID.nameUUIDFromBytes("openbank-risk-reference-curve-set-$VERSION:$asOf".toByteArray(Charsets.UTF_8))

    fun build(asOf: LocalDate, recordedAt: Instant): CurveSet = CurveSet(
        id = idFor(asOf),
        asOf = asOf,
        provenance = Provenance.SYNTHETIC,
        source = SOURCE,
        recordedAt = recordedAt,
        curves = QUOTES.mapValues { (index, qs) -> CurveBootstrap.bootstrap(index, asOf, qs) },
    )
}
