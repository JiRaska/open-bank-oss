// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class SepaSettlementMetricsTest {
    @Test
    fun `simpleclient exports five eager zero counters with only bounded rail and outcome labels`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val metrics = SepaSettlementMetrics().also { it.bindTo(registry) }
        assertThat(registry.meters).hasSize(5)
        registry.meters.forEach { meter ->
            assertThat(meter.id.name).isEqualTo("openbank.payment.unsettled")
            assertThat(meter.id.tags.map { it.key }).containsExactlyInAnyOrder("rail", "outcome")
            assertThat(meter.id.getTag("rail")).isEqualTo("SEPA")
            assertThat(meter.id.getTag("outcome"))
                .isIn(SepaSettlementMetrics.Outcome.entries.map { it.label })
            assertThat(registry.get(meter.id.name).tags(meter.id.tags).counter().count()).isZero()
        }
        assertThat(registry.scrape()).contains(
            """openbank_payment_unsettled_total{outcome="mismatch",rail="SEPA",} 0.0""",
        )
        metrics.unsettled(SepaSettlementMetrics.Outcome.CONFLICT)
        assertThat(registry.scrape()).contains(
            """openbank_payment_unsettled_total{outcome="conflict",rail="SEPA",} 1.0""",
        )
    }

    @Test
    fun `optional unresolved registry does not change the business outcome`() {
        val metrics = SepaSettlementMetrics()
        metrics.registryInstance = mockk<Instance<MeterRegistry>> { every { isResolvable } returns false }
        assertThatCode {
            metrics.register()
            metrics.unsettled(SepaSettlementMetrics.Outcome.MISMATCH)
        }.doesNotThrowAnyException()
    }
}
