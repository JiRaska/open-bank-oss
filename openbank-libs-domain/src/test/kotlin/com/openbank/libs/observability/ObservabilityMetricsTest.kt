// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * These three objects are the anti-drift seam ADR-0237/#2187 exists to protect: a producer and a
 * consumer must derive the same series name rather than each spelling it independently. The tests
 * pin the actual derivation (dots -> underscores, the feed- prefix, the enum vocabulary), not just
 * that the constants are non-blank, so a future edit to the naming rule fails here first.
 */
class ObservabilityMetricsTest {

    @Test
    fun `promSeriesName replaces every dot with an underscore`() {
        assertThat(WorkflowLivenessMetrics.promSeriesName("openbank.workflow.last_success.age_seconds"))
            .isEqualTo("openbank_workflow_last_success_age_seconds")
    }

    @Test
    fun `the derived PromQL series constants match their meter names dot-for-underscore`() {
        assertThat(
            WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SERIES,
        ).isEqualTo("openbank_workflow_last_success_age_seconds")
        assertThat(
            WorkflowLivenessMetrics.EXPECTED_INTERVAL_SERIES,
        ).isEqualTo("openbank_workflow_expected_interval_seconds")
        assertThat(WorkflowLivenessMetrics.SUCCESS_RECORDED_SERIES).isEqualTo("openbank_workflow_success_recorded")
    }

    @Test
    fun `isRenderableName accepts lowercase dotted names and rejects what promSeriesName cannot render exactly`() {
        assertThat(WorkflowLivenessMetrics.isRenderableName("openbank.workflow.run.duration")).isTrue()
        // Uppercase, a leading digit, or an illegal character all break the "dots only" promise
        // promSeriesName relies on — Micrometer's own naming convention would do more than a plain
        // dot replacement for these, so they must be flagged, not silently mis-rendered.
        assertThat(WorkflowLivenessMetrics.isRenderableName("Openbank.workflow")).isFalse()
        assertThat(WorkflowLivenessMetrics.isRenderableName("1openbank.workflow")).isFalse()
        assertThat(WorkflowLivenessMetrics.isRenderableName("openbank workflow")).isFalse()
        assertThat(WorkflowLivenessMetrics.isRenderableName("")).isFalse()
    }

    @Test
    fun `freshnessWorkflow tags a feed name with the ADR-0237 feed- prefix, never the bare name`() {
        assertThat(FeedFetchMetrics.freshnessWorkflow("cnb-daily-fixing")).isEqualTo("feed-cnb-daily-fixing")
        assertThat(
            FeedFetchMetrics.freshnessWorkflow("cnb-daily-fixing"),
        ).startsWith(FeedFetchMetrics.FRESHNESS_WORKFLOW_PREFIX)
    }

    @Test
    fun `FeedFetchOutcome carries five operationally distinct rows, EMPTY and HTTP_ERROR are not the same value`() {
        assertThat(FeedFetchOutcome.entries.map { it.name })
            .containsExactly("FETCHED", "EMPTY", "HTTP_ERROR", "PARSE_ERROR", "UNREACHABLE")
        assertThat(FeedFetchOutcome.EMPTY).isNotEqualTo(FeedFetchOutcome.FETCHED)
    }

    @Test
    fun `WorkflowRunMetrics series names carry the Micrometer-appended _seconds suffix explicitly`() {
        // Deliberately literal, not derived via promSeriesName: RUN_DURATION carries a base unit,
        // so Micrometer appends "_seconds" itself and a dot-replacement helper would under-render it.
        assertThat(
            WorkflowRunMetrics.RUN_DURATION_COUNT_SERIES,
        ).isEqualTo("openbank_workflow_run_duration_seconds_count")
        assertThat(WorkflowRunMetrics.RUN_DURATION_SUM_SERIES).isEqualTo("openbank_workflow_run_duration_seconds_sum")
        assertThat(WorkflowRunMetrics.RUN_BUDGET_SERIES).isEqualTo("openbank_workflow_run_budget_seconds")
        assertThat(WorkflowRunMetrics.OUTCOME_SUCCESS).isNotEqualTo(WorkflowRunMetrics.OUTCOME_FAILURE)
    }

    @Test
    fun `WorkflowRunMetrics reuses the same workflow tag name as WorkflowLivenessMetrics`() {
        assertThat(WorkflowRunMetrics.WORKFLOW_TAG).isEqualTo(WorkflowLivenessMetrics.WORKFLOW_TAG)
    }
}
