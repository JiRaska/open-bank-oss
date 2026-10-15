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

        assertThat(counter(registry, FraudVerdict.ALLOW, "SEPA")).isEqualTo(2.0)
        assertThat(counter(registry, FraudVerdict.DECLINE, "FX")).isEqualTo(1.0)
    }

    @Test
    fun `each allow-listed rail keeps its own label`() {
        val registry = SimpleMeterRegistry()
        val adapter = FraudMetricsAdapter(registry)

        // The vocabulary the wired scoring callers send: sepa-payment, domestic-payment,
        // sepa-instant, fx, card-processing.
        for (rail in listOf("SEPA", "DOMESTIC", "SCT_INST", "FX", "CARD")) {
            adapter.recordVerdict(FraudVerdict.ALLOW, rail)
            assertThat(counter(registry, FraudVerdict.ALLOW, rail)).describedAs("rail=$rail").isEqualTo(1.0)
        }
    }

    @Test
    fun `buckets caller-supplied garbage rails into unknown`() {
        val registry = SimpleMeterRegistry()
        val adapter = FraudMetricsAdapter(registry)

        // "SWIFT" is a real libs PaymentRail value but no wired scoring caller sends it; the rest
        // is plain caller-controlled garbage. None of it may reach the rail tag.
        for (garbage in listOf("SWIFT", "rail-please", "a".repeat(200), "sepa; DROP TABLE fraud_scores")) {
            adapter.recordVerdict(FraudVerdict.ALLOW, garbage)
        }

        assertThat(counter(registry, FraudVerdict.ALLOW, "unknown")).isEqualTo(4.0)
        // The raw caller string never becomes a series of its own.
        assertThat(registry.find("openbank.fraud.scores").meters())
            .allSatisfy { assertThat(it.id.getTag("rail")).isEqualTo("unknown") }
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
