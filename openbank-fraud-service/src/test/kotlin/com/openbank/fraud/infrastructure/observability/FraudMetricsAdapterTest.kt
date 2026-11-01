// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.fraud.infrastructure.observability

import com.openbank.fraud.domain.model.FraudVerdict
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FraudMetricsAdapterTest {

    @Test
    fun `increments a verdict-and-rail tagged counter`() {
        val registry = SimpleMeterRegistry()
        val adapter = FraudMetricsAdapter(registry)

        adapter.recordVerdict(FraudVerdict.ALLOW, "SEPA")
        adapter.recordVerdict(FraudVerdict.ALLOW, "SEPA")
        adapter.recordVerdict(FraudVerdict.DECLINE, "FX")
        adapter.recordVerdict(FraudVerdict.REVIEW, "CARD")

        assertThat(counter(registry, FraudVerdict.ALLOW, "SEPA")).isEqualTo(2.0)
        assertThat(counter(registry, FraudVerdict.DECLINE, "FX")).isEqualTo(1.0)
        assertThat(counter(registry, FraudVerdict.REVIEW, "CARD")).isEqualTo(1.0)
    }

    @Test
    fun `maps caller-controlled rail values to one unknown bucket`() {
        val registry = SimpleMeterRegistry()
        val adapter = FraudMetricsAdapter(registry)

        repeat(25) { adapter.recordVerdict(FraudVerdict.REVIEW, "attacker-controlled-$it") }
        adapter.recordVerdict(FraudVerdict.REVIEW, "sepa")

        assertThat(counter(registry, FraudVerdict.REVIEW, "unknown")).isEqualTo(26.0)
        assertThat(registry.meters.map { it.id.getTag("rail") }.toSet()).containsOnly("unknown")
    }

    @Test
    fun `is a no-op when no meter registry is present`() {
        // Slim slices without a Prometheus registry must not crash scoring.
        FraudMetricsAdapter(null).recordVerdict(FraudVerdict.ALLOW, "SEPA")
    }

    private fun counter(registry: SimpleMeterRegistry, verdict: FraudVerdict, rail: String): Double =
        registry.get("openbank.fraud.scores")
            .tag("service", "fraud")
            .tag("verdict", verdict.name)
            .tag("rail", rail)
            .counter()
            .count()
}
