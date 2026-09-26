// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Timer

/**
 * The fleet-standard percentile triple (ADR-0077): p50/p95/p99, published as both discrete
 * percentiles and a percentile histogram.
 *
 * Ten adapters (`DomainMetrics` and `LlmCallMetrics` here, plus eight service-local metrics
 * adapters) each redeclared this as three `private const val` fields purely to dodge detekt's
 * `MagicNumber` rule on the literals passed to `publishPercentiles` — every one of them carried a
 * comment saying so. One definition here means one place to change if the fleet's percentile
 * convention ever does, and it is what `openbank-libs/governance/detekt-baseline.xml` no longer
 * needs ten near-identical `MagicNumber` waivers for.
 */
object Percentiles {
    const val P50 = 0.5
    const val P95 = 0.95
    const val P99 = 0.99

    /** `[P50, P95, P99]`, in the order every adapter passed them to `publishPercentiles`. */
    val STANDARD = doubleArrayOf(P50, P95, P99)
}

/**
 * Apply the fleet-standard percentile publication ([Percentiles.STANDARD] plus a percentile
 * histogram) to a [Timer.Builder].
 *
 * Equivalent to `.publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram()` — every call
 * site that used to write those two lines by hand now writes this one. Nothing about the meter
 * this produces changes: same name, same tags, same percentiles, same histogram — only where the
 * three literals live.
 */
// Three named arguments, not a spread of Percentiles.STANDARD: detekt's SpreadOperator rule fires
// on `publishPercentiles(*array)` (a full array copy per call), and passing the three constants
// directly avoids it while keeping STANDARD around for callers that want the values as a group
// (e.g. PercentilesTest).
fun Timer.Builder.standardPercentiles(): Timer.Builder =
    this.publishPercentiles(Percentiles.P50, Percentiles.P95, Percentiles.P99).publishPercentileHistogram()

/** [DistributionSummary.Builder] counterpart of [Timer.Builder.standardPercentiles]. */
fun DistributionSummary.Builder.standardPercentiles(): DistributionSummary.Builder =
    this.publishPercentiles(Percentiles.P50, Percentiles.P95, Percentiles.P99).publishPercentileHistogram()
