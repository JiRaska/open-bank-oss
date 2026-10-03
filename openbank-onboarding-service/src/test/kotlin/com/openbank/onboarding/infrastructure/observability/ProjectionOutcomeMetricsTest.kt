// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two deployed alert rules (#6248) are `UNRECOGNISED > 0 and PROJECTED == 0` and
 * `SEEDED_UNKNOWN_PARTY > 0`, both `by (topic)`. Neither can evaluate unless the outcome and the
 * topic are separate label values on one series — folding UNRECOGNISED into a generic "handled"
 * is precisely the defect that let 15 DEVICE_ENROLLED events vanish while every alert read green.
 */
class ProjectionOutcomeMetricsTest {

    private fun count(registry: MeterRegistry, topic: String, outcome: ProjectionOutcomeMetrics.Outcome): Double? =
        registry.find("openbank.onboarding.projection.events")
            .tag("service", "onboarding")
            .tag("topic", topic)
            .tag("outcome", outcome.name)
            .counter()
            ?.count()

    @Test
    fun `each outcome gets its own series, so a quiet drop is not folded into success`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProjectionOutcomeMetrics(registry)

        metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED)
        metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)

        assertThat(count(registry, "sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED)).isEqualTo(1.0)
        assertThat(count(registry, "sca-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)).isEqualTo(1.0)
    }

    @Test
    fun `repeated records on the same topic and outcome accumulate on one series`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProjectionOutcomeMetrics(registry)

        repeat(3) { metrics.record("kyc-events-in", ProjectionOutcomeMetrics.Outcome.FAILED) }

        assertThat(count(registry, "kyc-events-in", ProjectionOutcomeMetrics.Outcome.FAILED)).isEqualTo(3.0)
    }

    @Test
    fun `topics are separate series, so one topic's traffic cannot mask another's silence`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProjectionOutcomeMetrics(registry)

        metrics.record("party-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED)
        metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)

        assertThat(count(registry, "sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED)).isNull()
        assertThat(count(registry, "party-events-in", ProjectionOutcomeMetrics.Outcome.UNRECOGNISED)).isNull()
    }

    @Test
    fun `SEEDED_UNKNOWN_PARTY is countable on its own, never as PROJECTED`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProjectionOutcomeMetrics(registry)

        metrics.record("sca-events-in", ProjectionOutcomeMetrics.Outcome.SEEDED_UNKNOWN_PARTY)

        assertThat(count(registry, "sca-events-in", ProjectionOutcomeMetrics.Outcome.SEEDED_UNKNOWN_PARTY))
            .isEqualTo(1.0)
        assertThat(count(registry, "sca-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED)).isNull()
    }

    @Test
    fun `cardinality stays bounded at one series per topic-outcome pair`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProjectionOutcomeMetrics(registry)

        repeat(10) {
            metrics.record("kyc-events-in", ProjectionOutcomeMetrics.Outcome.PROJECTED)
            metrics.record("kyc-events-in", ProjectionOutcomeMetrics.Outcome.FAILED)
        }

        assertThat(registry.find("openbank.onboarding.projection.events").counters()).hasSize(2)
    }

    @Test
    fun `recording with no meter registry is a no-op rather than a crash`() {
        val metrics = ProjectionOutcomeMetrics(null)

        // The slim test slices and any environment without Prometheus construct it this way;
        // a throw here would take the consumer down on the first message.
        ProjectionOutcomeMetrics.Outcome.entries.forEach { metrics.record("kyc-events-in", it) }
    }
}
