// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusNamingConvention
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Guards the producer/consumer seam of ADR-0160 mechanism 3.
 *
 * `DomainMetrics.registerWorkflowLiveness` emits the gauges; `openbank-control-liveness-sentinel`
 * reads them back over PromQL. For as long as each side spelled the name itself, they disagreed —
 * the producer emitted `openbank_workflow_last_success_age_seconds`, the sentinel queried
 * `openbank_workflow_liveness_last_success_age_seconds`. Every mechanism-3 collection therefore
 * returned an empty vector, and the sentinel reported "no stale heartbeats" unconditionally. Both
 * sides had unit tests; each hardcoded its own literal, so neither could see the disagreement.
 *
 * The tests below are deliberately about the *seam*, not about either side's spelling:
 *
 *  1. the meter names a real registration actually produces are the [WorkflowLivenessMetrics]
 *     constants (so the shared constants describe reality, not intent), and
 *  2. those meter names render, **under Micrometer's own `PrometheusNamingConvention`**, to exactly
 *     the series names the sentinel queries. That is the step no amount of testing either side in
 *     isolation could cover, and it is checked against the real convention rather than against this
 *     repo's hand-rolled dot -> underscore helper — otherwise the helper would only be proving
 *     itself.
 */
class WorkflowLivenessMetricNamingTest {

    private fun withRegistry(reg: MeterRegistry): DomainMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        every { inst.isResolvable } returns true
        every { inst.get() } returns reg
        return DomainMetrics().apply { registryInstance = inst }
    }

    @Test
    fun `a real registration emits exactly the meter names the shared constants declare`() {
        val reg = SimpleMeterRegistry()

        withRegistry(reg).registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))

        assertThat(reg.meters.map { it.id.name })
            .describedAs("the constants must describe what registerWorkflowLiveness really emits")
            .contains(
                WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS,
                WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS,
                WorkflowLivenessMetrics.SUCCESS_RECORDED,
            )
        assertThat(reg.meters.mapNotNull { it.id.getTag(WorkflowLivenessMetrics.WORKFLOW_TAG) })
            .describedAs("every gauge must carry the workflow tag the sentinel keys its map by")
            .containsOnly("standing-order-execution")
    }

    @Test
    fun `the queried series names are what Micrometer's Prometheus convention renders`() {
        val convention = PrometheusNamingConvention()

        assertThat(convention.name(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS, Meter.Type.GAUGE))
            .describedAs("the sentinel's PromQL must ask for the series Prometheus actually exposes")
            .isEqualTo(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SERIES)
        assertThat(convention.name(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS, Meter.Type.GAUGE))
            .isEqualTo(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SERIES)
        assertThat(convention.name(WorkflowLivenessMetrics.SUCCESS_RECORDED, Meter.Type.GAUGE))
            .isEqualTo(WorkflowLivenessMetrics.SUCCESS_RECORDED_SERIES)
    }

    @Test
    fun `the series names are pinned literally so a constant rename cannot silently move them`() {
        // Dashboards, PromQL rules and the sentinel all address these by string. Renaming the Kotlin
        // constant is free; renaming the SERIES is a breaking observability change and must be a
        // deliberate edit here.
        assertThat(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SERIES)
            .isEqualTo("openbank_workflow_last_success_age_seconds")
        assertThat(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SERIES)
            .isEqualTo("openbank_workflow_expected_interval_seconds")
        assertThat(WorkflowLivenessMetrics.SUCCESS_RECORDED_SERIES)
            .isEqualTo("openbank_workflow_success_recorded")
    }

    @Test
    fun `both meter names stay simple enough for the rendering helper to be exact`() {
        // promSeriesName is only faithful for lower-snake-plus-dots names with no base unit; if
        // someone adds an uppercase letter or a unit suffix, the helper silently stops matching what
        // Prometheus exposes. Pin the precondition rather than discover it in production.
        assertThat(WorkflowLivenessMetrics.isRenderableName(WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SECONDS)).isTrue()
        assertThat(WorkflowLivenessMetrics.isRenderableName(WorkflowLivenessMetrics.EXPECTED_INTERVAL_SECONDS)).isTrue()
        assertThat(WorkflowLivenessMetrics.isRenderableName(WorkflowLivenessMetrics.SUCCESS_RECORDED)).isTrue()
    }
}
