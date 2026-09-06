// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fx.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #4221 layer 2: a synthetic fraud verdict must be distinguishable from a real one, and a pod that
 * has never scored anything must not read as healthy. Every assertion here is about a value an
 * alert would read, not about the class's shape.
 */
class FraudScoringMetricsTest {

    private val registry = SimpleMeterRegistry()

    private fun bound(): FraudScoringMetrics = FraudScoringMetrics().also { it.bindTo(registry) }

    private fun gauge(): Double = registry.get(FraudScoringMetrics.DEGRADED_METRIC)
        .tag("service", FraudScoringMetrics.SERVICE)
        .tag("rail", FraudScoringMetrics.RAIL)
        .gauge().value()

    private fun counter(result: String): Double = registry.get(FraudScoringMetrics.OUTCOMES_METRIC)
        .tag("service", FraudScoringMetrics.SERVICE)
        .tag("rail", FraudScoringMetrics.RAIL)
        .tag("result", result)
        .counter().count()

    @Test
    fun `a pod that has scored nothing reads minus one, never a healthy zero`() {
        bound()

        assertThat(gauge()).isEqualTo(FraudScoringMetrics.NEVER_ATTEMPTED.toDouble())
        assertThat(FraudScoringMetrics.NEVER_ATTEMPTED).isEqualTo(-1L)
    }

    @Test
    fun `both outcome counters exist at zero before any scoring happens`() {
        bound()

        assertThat(counter(FraudScoringMetrics.RESULT_REAL)).isZero()
        assertThat(counter(FraudScoringMetrics.RESULT_SYNTHETIC)).isZero()
    }

    @Test
    fun `a real verdict drives the gauge to zero and increments only the real counter`() {
        val metrics = bound()

        metrics.recordReal()

        assertThat(gauge()).isZero()
        assertThat(metrics.degradedValue()).isZero()
        assertThat(counter(FraudScoringMetrics.RESULT_REAL)).isEqualTo(1.0)
        assertThat(counter(FraudScoringMetrics.RESULT_SYNTHETIC)).isZero()
    }

    @Test
    fun `a synthetic verdict drives the gauge to one and increments only the synthetic counter`() {
        val metrics = bound()

        metrics.recordSynthetic()

        assertThat(gauge()).isEqualTo(1.0)
        assertThat(metrics.degradedValue()).isEqualTo(1L)
        assertThat(counter(FraudScoringMetrics.RESULT_SYNTHETIC)).isEqualTo(1.0)
        assertThat(counter(FraudScoringMetrics.RESULT_REAL)).isZero()
    }

    @Test
    fun `the gauge tracks the most recent attempt, not the worst one seen`() {
        val metrics = bound()

        metrics.recordSynthetic()
        metrics.recordSynthetic()
        metrics.recordReal()

        assertThat(gauge()).isZero()
        assertThat(counter(FraudScoringMetrics.RESULT_SYNTHETIC)).isEqualTo(2.0)
        assertThat(counter(FraudScoringMetrics.RESULT_REAL)).isEqualTo(1.0)

        metrics.recordSynthetic()

        assertThat(gauge()).isEqualTo(1.0)
    }

    @Test
    fun `recording without a registry keeps the state but touches no counter`() {
        val metrics = FraudScoringMetrics()

        metrics.recordSynthetic()
        assertThat(metrics.degradedValue()).isEqualTo(1L)
        metrics.recordReal()
        assertThat(metrics.degradedValue()).isZero()

        assertThat(registry.find(FraudScoringMetrics.OUTCOMES_METRIC).counters()).isEmpty()
    }

    @Test
    fun `register binds when the registry is resolvable and skips when it is not`() {
        val resolvable = mockk<Instance<MeterRegistry>>()
        every { resolvable.isResolvable } returns true
        every { resolvable.get() } returns registry
        FraudScoringMetrics().also { it.registryInstance = resolvable }.register()

        assertThat(gauge()).isEqualTo(FraudScoringMetrics.NEVER_ATTEMPTED.toDouble())

        val empty = SimpleMeterRegistry()
        val unresolvable = mockk<Instance<MeterRegistry>>()
        every { unresolvable.isResolvable } returns false
        FraudScoringMetrics().also { it.registryInstance = unresolvable }.register()

        assertThat(empty.meters).isEmpty()
    }

    @Test
    fun `the gauge holds a strong reference so it survives a garbage collection`() {
        bound().recordSynthetic()

        System.gc()

        assertThat(gauge()).isEqualTo(1.0)
    }
}
