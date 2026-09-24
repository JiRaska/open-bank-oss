// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * These constants exist because the producer and the consumer of the ADR-0160 mechanism-3 gauges
 * once spelled the metric name independently and disagreed, so every collection returned an empty
 * vector and the control could only ever report "nothing stale" (#2148, #2187). A test that
 * hardcodes one side's literal is exactly the test that could not see that; these assert the
 * RELATIONSHIP between the meter name and the PromQL series instead.
 */
class WorkflowMetricNamesTest {

    @Test
    fun `each liveness series is its meter name with dots replaced by underscores`() {
        assertThat(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SERIES)
            .isEqualTo(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS.replace('.', '_'))
        assertThat(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SERIES)
            .isEqualTo(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS.replace('.', '_'))
        assertThat(WorkflowLivenessMetrics.SUCCESS_RECORDED_SERIES)
            .isEqualTo(WorkflowLivenessMetrics.SUCCESS_RECORDED.replace('.', '_'))
    }

    @Test
    fun `promSeriesName leaves a name with no dots untouched and is idempotent`() {
        assertThat(WorkflowLivenessMetrics.promSeriesName("already_flat")).isEqualTo("already_flat")
        val once = WorkflowLivenessMetrics.promSeriesName(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        assertThat(WorkflowLivenessMetrics.promSeriesName(once)).isEqualTo(once)
    }

    @Test
    fun `promSeriesName is only claimed exact for names isRenderableName accepts`() {
        WorkflowLivenessMetrics.let {
            assertThat(it.isRenderableName(it.LAST_SUCCESS_AGE_SECONDS)).isTrue()
            assertThat(it.isRenderableName(it.EXPECTED_INTERVAL_SECONDS)).isTrue()
            assertThat(it.isRenderableName(it.SUCCESS_RECORDED)).isTrue()
        }
    }

    @Test
    fun `isRenderableName rejects the shapes Micrometer would sanitize or suffix`() {
        listOf(
            "Openbank.workflow", // uppercase
            "0leading.digit", // must start with a letter
            "has-a-dash", // '-' is sanitized to '_' by the naming convention
            "has space",
            "has/slash",
            "", // empty
            ".leading.dot",
        ).forEach {
            assertThat(WorkflowLivenessMetrics.isRenderableName(it))
                .describedAs("isRenderableName(%s)", it)
                .isFalse()
        }
    }

    @Test
    fun `the liveness gauges share one workflow tag with the run-duration meters`() {
        assertThat(WorkflowRunMetrics.WORKFLOW_TAG).isEqualTo(WorkflowLivenessMetrics.WORKFLOW_TAG)
        assertThat(WorkflowRunMetrics.WORKFLOW_TAG).isEqualTo("workflow")
    }

    @Test
    fun `the run-duration series are the timer's base-unit-suffixed components, spelled consistently`() {
        // The timer's base unit is seconds, so Micrometer appends `_seconds` that promSeriesName
        // cannot know about — which is why these are written out. They must still agree with the
        // meter name they describe, or the alert queries a series nothing emits.
        val base = WorkflowRunMetrics.RUN_DURATION.replace('.', '_') + "_seconds"
        assertThat(WorkflowRunMetrics.RUN_DURATION_COUNT_SERIES).isEqualTo(base + "_count")
        assertThat(WorkflowRunMetrics.RUN_DURATION_SUM_SERIES).isEqualTo(base + "_sum")
    }

    @Test
    fun `the budget gauge carries its unit in the meter name so no suffix is appended`() {
        assertThat(WorkflowRunMetrics.RUN_BUDGET_SECONDS).endsWith("_seconds")
        assertThat(WorkflowRunMetrics.RUN_BUDGET_SERIES)
            .isEqualTo(WorkflowLivenessMetrics.promSeriesName(WorkflowRunMetrics.RUN_BUDGET_SECONDS))
    }

    @Test
    fun `the run outcome vocabulary is closed and its two values are distinct`() {
        assertThat(WorkflowRunMetrics.OUTCOME_TAG).isEqualTo("outcome")
        assertThat(WorkflowRunMetrics.OUTCOME_SUCCESS).isNotEqualTo(WorkflowRunMetrics.OUTCOME_FAILURE)
        assertThat(listOf(WorkflowRunMetrics.OUTCOME_SUCCESS, WorkflowRunMetrics.OUTCOME_FAILURE))
            .allSatisfy { assertThat(it).matches { v -> Regex("^[a-z]+$").matches(v) } }
    }

    @Test
    fun `no two workflow meter names collide once rendered as PromQL series`() {
        val names = listOf(
            WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS,
            WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS,
            WorkflowLivenessMetrics.SUCCESS_RECORDED,
            WorkflowRunMetrics.RUN_DURATION,
            WorkflowRunMetrics.RUN_BUDGET_SECONDS,
        )
        assertThat(names.map { WorkflowLivenessMetrics.promSeriesName(it) }).doesNotHaveDuplicates()
    }
}
