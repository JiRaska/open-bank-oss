// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

/**
 * The single place the per-run DURATION metric names are written down — the "how long did it take"
 * half of the ADR-0160 mechanism-3 pair, beside [WorkflowLivenessMetrics]' "did it run at all".
 *
 * **Why a second primitive rather than a percentile over the traces.** The oversight sweep's
 * duration was, until this existed, only observable through `traces_spanmetrics_latency_bucket`,
 * and that instrument **saturates**: its `le` set is `0.1, 0.25, 0.5, 1, 2, 5, +Inf`, every sweep
 * lands in `(5s, +Inf]`, and `histogram_quantile(0.99, …)` therefore returns exactly `5.00` on
 * every run. No threshold above 5s is expressible from it at all, so a sweep degrading from 6s to
 * 300s is indistinguishable from a healthy one (#6169, measured in #6168). Worse, the window holds
 * ~15–21 spans, so that "p99" is not a percentile — it is the maximum. The fix is not a retuned
 * rule; it is an instrument the job owns.
 *
 * **Why the timer publishes no percentiles.** Same arithmetic one layer down: a 30-minute job
 * contributes ~4 observations to a 2-hour window, and a quantile over four samples is the maximum
 * with extra steps. Publishing `{quantile="0.99"}` here would hand the next reader the exact
 * misleading number this primitive exists to replace. What is published is `_count`, `_sum` and
 * `_max`; the alert reads `_sum / _count` — a mean over whole runs, which is defined for any
 * sample size ≥ 1.
 *
 * **Do not alert on `_max`.** Micrometer's timer max is a decaying window (a few minutes by
 * default), so between two sweeps it falls back to `0`. It is fine on a dashboard and is a trap in
 * a rule: `> budget` would be true for a couple of minutes after each run and false for the other
 * twenty-eight, which is the never-matures shape all over again.
 *
 * Names live here, in the domain module, for the reason [WorkflowLivenessMetrics] gives: a metric
 * name spelled independently by producer and consumer is how mechanism 3 spent months querying a
 * series nothing emitted (#2187).
 */
object WorkflowRunMetrics {

    /**
     * Meter name (Micrometer, dotted) of the per-run duration timer. Its base unit is seconds, so
     * the Prometheus registry renders it as `openbank_workflow_run_duration_seconds_{count,sum,max}`
     * — the `_seconds` is appended by the naming convention and is NOT part of this constant, which
     * is why the series names below are written out rather than derived by
     * [WorkflowLivenessMetrics.promSeriesName] (that helper is only exact for names carrying no
     * base unit, as its own KDoc says).
     */
    const val RUN_DURATION: String = "openbank.workflow.run.duration"

    /**
     * Meter name of the companion budget gauge: the duration above which this workflow's mean run
     * is considered degraded, declared by the code that owns the job.
     *
     * A gauge rather than a constant in the rule file, mirroring
     * [WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS]: the alert then needs no per-service
     * threshold, no service list, and cannot go stale when a job's shape changes — the same reason
     * `WorkflowLivenessStale` compares two series instead of hard-coding 2 days.
     *
     * It carries its unit in the meter name (`_seconds`) because it is a plain gauge, so no base
     * unit is appended and the Prometheus series name is this string with the dots replaced.
     */
    const val RUN_BUDGET_SECONDS: String = "openbank.workflow.run.budget_seconds"

    /** Tag carrying the workflow's stable low-cardinality name — the same values as liveness. */
    const val WORKFLOW_TAG: String = WorkflowLivenessMetrics.WORKFLOW_TAG

    /**
     * Tag separating a run that completed from one that threw. Closed vocabulary: a failure that
     * fails FAST would otherwise pull the mean down and mask a slow success, so the two are
     * separable at triage time even though the alert deliberately aggregates over both (wall-clock
     * spent failing is still wall-clock spent).
     */
    const val OUTCOME_TAG: String = "outcome"

    const val OUTCOME_SUCCESS: String = "success"
    const val OUTCOME_FAILURE: String = "failure"

    /** PromQL series name of the run-count component of [RUN_DURATION]. */
    const val RUN_DURATION_COUNT_SERIES: String = "openbank_workflow_run_duration_seconds_count"

    /** PromQL series name of the summed-seconds component of [RUN_DURATION]. */
    const val RUN_DURATION_SUM_SERIES: String = "openbank_workflow_run_duration_seconds_sum"

    /** PromQL series name of [RUN_BUDGET_SECONDS]. */
    const val RUN_BUDGET_SERIES: String = "openbank_workflow_run_budget_seconds"
}
