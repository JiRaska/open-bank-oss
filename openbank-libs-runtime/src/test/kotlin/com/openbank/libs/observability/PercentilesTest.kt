// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Proves [Timer.Builder.standardPercentiles] / [DistributionSummary.Builder.standardPercentiles]
 * register a meter identical — same id, same configured percentiles, same histogram flag — to
 * what every adapter's hand-built `.publishPercentiles(0.5, 0.95, 0.99).publishPercentileHistogram()`
 * produced before the migration (issue #10910). A dashboard/alert depends on the registered meter
 * id and its percentile configuration, not on which line of code built it.
 *
 * Uses a real [PrometheusMeterRegistry] scrape, not [io.micrometer.core.instrument.simple.SimpleMeterRegistry],
 * for the histogram assertion: `SimpleMeterRegistry` never populates `HistogramSnapshot.histogramCounts()`
 * regardless of `publishPercentileHistogram()`, so that field cannot distinguish the two — measured by
 * running this test against `SimpleMeterRegistry` first, which reported empty buckets for BOTH the
 * helper and the correctly-configured hand-built comparison. A Prometheus scrape's `_bucket` lines are
 * what `publishPercentileHistogram()` actually controls (ADR-0077's dashboards read a Prometheus
 * histogram, not the in-process snapshot), so they are what this test checks.
 */
class PercentilesTest {

    @Test
    fun `STANDARD is the fleet p50-p95-p99 triple in publishPercentiles order`() {
        assertThat(Percentiles.STANDARD).containsExactly(0.5, 0.95, 0.99)
    }

    @Test
    fun `Timer standardPercentiles matches the hand-built call it replaces`() {
        val viaHelper = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        Timer.builder("openbank.test.timer")
            .tags("service", "x")
            .standardPercentiles()
            .register(viaHelper)
            .record(Duration.ofMillis(10))

        val viaHandBuilt = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        Timer.builder("openbank.test.timer")
            .tags("service", "x")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(viaHandBuilt)
            .record(Duration.ofMillis(10))

        val helperTimer = viaHelper.find("openbank.test.timer").tag("service", "x").timer()
        val handBuiltTimer = viaHandBuilt.find("openbank.test.timer").tag("service", "x").timer()
        assertThat(helperTimer).isNotNull
        assertThat(handBuiltTimer).isNotNull

        // Same registered meter id (name + tags) — what a dashboard/alert actually selects on.
        assertThat(helperTimer!!.id).isEqualTo(handBuiltTimer!!.id)

        // Same configured percentiles.
        assertThat(helperTimer.takeSnapshot().percentileValues().map { it.percentile() })
            .containsExactlyInAnyOrderElementsOf(handBuiltTimer.takeSnapshot().percentileValues().map { it.percentile() })

        // Same histogram flag, proved via a real scrape: `_bucket` series only appear when
        // publishPercentileHistogram() was called. This is the assertion that actually fails if
        // that call is dropped from standardPercentiles() — the percentile-values assertion above
        // does not depend on it and stays green either way. Timer renders its base unit into the
        // series name (`_seconds_bucket`), so match on the `openbank_test_timer` prefix + `_bucket`
        // rather than a hardcoded full name.
        assertThat(viaHelper.scrape().lines())
            .anyMatch { it.startsWith("openbank_test_timer") && it.contains("_bucket") }
        assertThat(viaHandBuilt.scrape().lines())
            .anyMatch { it.startsWith("openbank_test_timer") && it.contains("_bucket") }
    }

    @Test
    fun `DistributionSummary standardPercentiles matches the hand-built call it replaces`() {
        val viaHelper = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        DistributionSummary.builder("openbank.test.summary")
            .tags("service", "x")
            .standardPercentiles()
            .register(viaHelper)
            .record(10.0)

        val viaHandBuilt = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        DistributionSummary.builder("openbank.test.summary")
            .tags("service", "x")
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(viaHandBuilt)
            .record(10.0)

        val helperSummary = viaHelper.find("openbank.test.summary").tag("service", "x").summary()
        val handBuiltSummary = viaHandBuilt.find("openbank.test.summary").tag("service", "x").summary()
        assertThat(helperSummary).isNotNull
        assertThat(handBuiltSummary).isNotNull
        assertThat(helperSummary!!.id).isEqualTo(handBuiltSummary!!.id)

        assertThat(helperSummary.takeSnapshot().percentileValues().map { it.percentile() })
            .containsExactlyInAnyOrderElementsOf(handBuiltSummary.takeSnapshot().percentileValues().map { it.percentile() })

        assertThat(viaHelper.scrape()).contains("openbank_test_summary_bucket")
        assertThat(viaHandBuilt.scrape()).contains("openbank_test_summary_bucket")
    }
}
