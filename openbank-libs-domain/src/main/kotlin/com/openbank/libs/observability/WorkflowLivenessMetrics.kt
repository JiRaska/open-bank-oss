// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

/**
 * The single place the ADR-0160 mechanism-3 workflow-liveness metric names are written down.
 *
 * **Why this object exists.** The names used to be string literals on both sides of the seam: the
 * producer registered `openbank.workflow.last_success.age_seconds` in `DomainMetrics`, while the
 * consumer — `openbank-control-liveness-sentinel` — queried
 * `openbank_workflow_liveness_last_success_age_seconds`. Nothing emitted that second name, so every
 * mechanism-3 collection returned an empty vector and the sentinel could only ever report "no stale
 * heartbeats". Both sides had tests; both tests hardcoded their own side's literal, so neither could
 * see the disagreement. That is the same failure shape as the dead schedulers the mechanism exists
 * to catch (#2148, #2187): a control that is structurally incapable of reporting a problem still
 * reads green.
 *
 * Anything that registers or queries these gauges must go through the constants here rather than
 * spell the name again.
 *
 * Pure Kotlin on purpose: the producer lives in `openbank-libs-runtime` (Micrometer) and the
 * consumer in a service's application layer, so the shared truth has to sit in the domain module
 * that both can depend on (ADR-0122).
 */
object WorkflowLivenessMetrics {

    /** Meter name (Micrometer, dotted) of the age-of-last-success gauge. */
    const val LAST_SUCCESS_AGE_SECONDS: String = "openbank.workflow.last_success.age_seconds"

    /** Meter name (Micrometer, dotted) of the companion expected-run-interval gauge. */
    const val EXPECTED_INTERVAL_SECONDS: String = "openbank.workflow.expected_interval_seconds"

    /**
     * Meter name (Micrometer, dotted) of the has-ever-succeeded flag: `0` from registration until
     * the first `recordSuccess()`, `1` from then on.
     *
     * **Why this is a separate series rather than a magic value in [LAST_SUCCESS_AGE_SECONDS].**
     * The age gauge is seeded at registration time, so on a fresh pod "the job has never succeeded"
     * and "the job succeeded a second ago" both read as a small age — the age alone cannot tell
     * them apart, and the alert must not care (a never-run job crosses its own threshold once its
     * grace elapses, exactly like one that stopped running). What *does* care is triage: the
     * control-liveness-sentinel's finding says something different about a job that has produced
     * one success and then stopped than about a job that has produced none since boot. This flag
     * carries that one bit, and nothing else — the age gauge stays a plain age with no sentinel
     * values in it.
     */
    const val SUCCESS_RECORDED: String = "openbank.workflow.success.recorded"

    /** Tag carrying the workflow's stable low-cardinality name, e.g. `standing-order-execution`. */
    const val WORKFLOW_TAG: String = "workflow"

    /** PromQL series name of [LAST_SUCCESS_AGE_SECONDS] — what a Prometheus query must ask for. */
    val LAST_SUCCESS_AGE_SERIES: String = promSeriesName(LAST_SUCCESS_AGE_SECONDS)

    /** PromQL series name of [EXPECTED_INTERVAL_SECONDS]. */
    val EXPECTED_INTERVAL_SERIES: String = promSeriesName(EXPECTED_INTERVAL_SECONDS)

    /** PromQL series name of [SUCCESS_RECORDED]. */
    val SUCCESS_RECORDED_SERIES: String = promSeriesName(SUCCESS_RECORDED)

    /**
     * Renders a Micrometer meter name as the Prometheus series name it is scraped under.
     *
     * Micrometer's `PrometheusNamingConvention` does more than this in general (it also sanitizes
     * illegal characters and appends a base-unit suffix), so this is only exact for names that are
     * already lower-snake plus dots and carry no base unit — which the two constants above are, and
     * which [isRenderableName] pins.
     */
    fun promSeriesName(meterName: String): String = meterName.replace('.', '_')

    /**
     * True when a meter name is simple enough that [promSeriesName] renders it exactly as Micrometer
     * would: lowercase letters, digits, `_` and `.` only, starting with a letter.
     */
    fun isRenderableName(meterName: String): Boolean = RENDERABLE.matches(meterName)

    private val RENDERABLE = Regex("^[a-z][a-z0-9_.]*$")
}
