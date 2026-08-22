// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Guards the producer/consumer seam of the per-run duration signal (#6169): `registerWorkflowRun`
 * emits the meters, `WorkflowRunDurationHigh` in
 * `openbank-infra/gitops/components/observability/prometheus-rules-workflow-liveness.yaml` reads
 * them back over PromQL.
 *
 * The seam is checked against a REAL `PrometheusMeterRegistry` scrape rather than against this
 * repo's dot -> underscore helper, because the helper cannot model the part that actually bites: a
 * `Timer` carries a base unit, so Micrometer appends `_seconds` that appears in no Kotlin constant,
 * and then the exposition adds `_count` / `_sum` on top. A rule addressing the name a developer
 * would guess matches nothing, silently — the #2187 shape, and the one that made the whole
 * mechanism-3 chain report "no stale heartbeats" for months.
 *
 * Sibling of [WorkflowLivenessMetricNamingTest]; the same argument, one instrument further along.
 */
class WorkflowRunMetricNamingTest {

    private fun withRegistry(reg: MeterRegistry): DomainMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        every { inst.isResolvable } returns true
        every { inst.get() } returns reg
        return DomainMetrics().apply { registryInstance = inst }
    }

    private fun scrapeOf(block: (DomainMetrics) -> Unit): String {
        val reg = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        block(withRegistry(reg))
        return reg.scrape()
    }

    @Test
    fun `a real registration emits exactly the meter names the shared constants declare`() {
        val reg = SimpleMeterRegistry()

        withRegistry(reg).registerWorkflowRun("agent-oversight-sweep", Duration.ofMinutes(5))

        assertThat(reg.meters.map { it.id.name })
            .describedAs("the constants must describe what registerWorkflowRun really emits")
            .contains(WorkflowRunMetrics.RUN_DURATION, WorkflowRunMetrics.RUN_BUDGET_SECONDS)
        assertThat(reg.meters.mapNotNull { it.id.getTag(WorkflowRunMetrics.WORKFLOW_TAG) })
            .describedAs("both instruments key on the same tag the liveness gauges use")
            .containsOnly("agent-oversight-sweep")
    }

    @Test
    fun `the scraped series names are exactly the ones the alert expression asks for`() {
        val scrape = scrapeOf { dm ->
            dm.registerWorkflowRun("agent-oversight-sweep", Duration.ofMinutes(5))
                .record(Duration.ofSeconds(7))
        }

        // Written as literals on purpose: these three strings appear verbatim in
        // prometheus-rules-workflow-liveness.yaml, and a rule cannot be compiled against a constant.
        assertThat(scrape).contains(WorkflowRunMetrics.RUN_DURATION_SUM_SERIES)
        assertThat(scrape).contains(WorkflowRunMetrics.RUN_DURATION_COUNT_SERIES)
        assertThat(scrape).contains(WorkflowRunMetrics.RUN_BUDGET_SERIES)
        assertThat(WorkflowRunMetrics.RUN_DURATION_SUM_SERIES)
            .isEqualTo("openbank_workflow_run_duration_seconds_sum")
        assertThat(WorkflowRunMetrics.RUN_DURATION_COUNT_SERIES)
            .isEqualTo("openbank_workflow_run_duration_seconds_count")
        assertThat(WorkflowRunMetrics.RUN_BUDGET_SERIES)
            .isEqualTo("openbank_workflow_run_budget_seconds")
    }

    @Test
    fun `no quantile series is published, so no one can point a saturating percentile at four samples`() {
        val scrape = scrapeOf { dm ->
            dm.registerWorkflowRun("agent-oversight-sweep", Duration.ofMinutes(5))
                .record(Duration.ofSeconds(7))
        }

        // The whole reason this instrument exists is that a percentile over a sparse periodic job
        // returns the maximum (traces_spanmetrics_latency_bucket saturating at le=5 was the same
        // arithmetic one layer up, #6168). Publishing {quantile="0.99"} here would hand the next
        // reader that exact number back.
        val runLines = scrape.lines().filter { it.startsWith("openbank_workflow_run_duration") }
        assertThat(runLines).isNotEmpty
        assertThat(runLines.filter { it.contains("quantile=") })
            .describedAs("a quantile over ~4 observations per window is the maximum with extra steps")
            .isEmpty()
        assertThat(runLines.filter { it.contains("le=") })
            .describedAs("no bucket series either — the alert reads sum/count, nothing else")
            .isEmpty()
    }
}
