// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class FraudHoldDurationMeterFilterTest {

    /** Registered the way Temporal's MicrometerClientStatsReporter registers a workflow timer. */
    private fun timer(registry: SimpleMeterRegistry, name: String): Timer =
        Timer.builder(name).publishPercentileHistogram(true).register(registry)

    @Test
    fun `a 6 hour hold lands in a finite bucket, not only in +Inf`() {
        val registry = SimpleMeterRegistry().also { it.config().meterFilter(FraudHoldDurationMeterFilter.filter()) }
        val t = timer(registry, DomesticPaymentWorkflowImpl.FRAUD_HOLD_DURATION_METRIC)
        t.record(Duration.ofHours(6))

        val buckets = t.takeSnapshot().histogramCounts().map { it.bucket(TimeUnit.HOURS) }
        assertThat(buckets).containsExactlyElementsOf(
            FraudHoldDurationMeterFilter.BUCKETS.map {
                it.toMinutes() / 60.0
            },
        )
        assertThat(t.takeSnapshot().histogramCounts().first { it.bucket(TimeUnit.HOURS) == 8.0 }.count()).isEqualTo(1.0)
    }

    @Test
    fun `other timers are left alone`() {
        val config = DistributionStatisticConfig.builder().percentilesHistogram(true).build()
        val id = Meter.Id("some.other.timer", Tags.empty(), null, null, Meter.Type.TIMER)

        assertThat(FraudHoldDurationMeterFilter.filter().configure(id, config)).isSameAs(config)
    }
}
