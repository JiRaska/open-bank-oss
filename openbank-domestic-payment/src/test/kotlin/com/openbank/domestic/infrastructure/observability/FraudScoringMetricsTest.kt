// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.observability

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves the alert expressions in
 * `openbank-infra/gitops/components/payments/prometheus-rules.yaml` (group
 * `openbank.domestic-payment.fraud-scoring`) match series this code really produces.
 *
 * The assertions are made against a **real** [PrometheusMeterRegistry] scrape rather than against
 * Micrometer meter ids, because the alert is written in the scraped vocabulary: dots become
 * underscores, a counter gains a `_total` suffix, and tags are rendered alphabetically. Asserting
 * the meter id would only prove this test's own idea of that mapping.
 *
 * Falsification order matters here: an alert written on a counter that has never been incremented
 * matches nothing forever, and that is indistinguishable from a healthy fleet. So the first test
 * asserts the series exist on a **fresh** registry — before anything has been recorded.
 */
class FraudScoringMetricsTest {

    private fun metrics(registry: PrometheusMeterRegistry) = FraudScoringMetrics().apply { bindTo(registry) }

    private fun registry() = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    @Test
    fun `both series exist on the first scrape, before anything has been scored`() {
        val registry = registry()
        metrics(registry)

        val scrape = registry.scrape()

        // -1, not 0: a pod that has never scored anything must not read as "scoring is healthy".
        assertThat(scrape)
            .describedAs("the degraded gauge must be published from boot, at NEVER_ATTEMPTED")
            .contains("""openbank_fraud_scoring_degraded{rail="DOMESTIC",service="domestic"} -1.0""")
        // Micrometer creates a counter on registration, not on first increment — this is what lets
        // an alert on the synthetic counter be written at all.
        assertThat(scrape)
            .describedAs("the synthetic counter must exist at zero before the first failure")
            .contains(
                """openbank_fraud_scoring_outcomes_total{rail="DOMESTIC",result="synthetic",service="domestic"} 0.0""",
            )
        assertThat(scrape).contains(
            """openbank_fraud_scoring_outcomes_total{rail="DOMESTIC",result="real",service="domestic"} 0.0""",
        )
    }

    @Test
    fun `a synthetic outcome moves the gauge to 1 and increments only the synthetic counter`() {
        val registry = registry()
        val metrics = metrics(registry)

        metrics.recordSynthetic()

        val scrape = registry.scrape()
        assertThat(scrape).contains(
            """openbank_fraud_scoring_degraded{rail="DOMESTIC",service="domestic"} 1.0""",
        )
        assertThat(scrape).contains(
            """openbank_fraud_scoring_outcomes_total{rail="DOMESTIC",result="synthetic",service="domestic"} 1.0""",
        )
        assertThat(scrape).contains(
            """openbank_fraud_scoring_outcomes_total{rail="DOMESTIC",result="real",service="domestic"} 0.0""",
        )
    }

    @Test
    fun `a real outcome after a synthetic one clears the gauge back to 0`() {
        val registry = registry()
        val metrics = metrics(registry)

        metrics.recordSynthetic()
        metrics.recordReal()

        assertThat(metrics.degradedValue()).isZero()
        assertThat(registry.scrape()).contains(
            """openbank_fraud_scoring_degraded{rail="DOMESTIC",service="domestic"} 0.0""",
        )
    }

    @Test
    fun `never-attempted is a distinct value from healthy`() {
        assertThat(FraudScoringMetrics.NEVER_ATTEMPTED)
            .describedAs("boot state must not collide with the healthy value the alert ignores")
            .isNotEqualTo(0L)
        assertThat(FraudScoringMetrics().degradedValue()).isEqualTo(FraudScoringMetrics.NEVER_ATTEMPTED)
    }
}
