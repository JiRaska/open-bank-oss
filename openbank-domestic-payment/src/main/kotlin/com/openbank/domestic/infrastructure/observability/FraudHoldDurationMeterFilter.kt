// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Duration

/**
 * Gives `openbank_domestic_fraud_hold_duration` buckets that can describe a fraud hold.
 *
 * Temporal's `MicrometerClientStatsReporter` registers every workflow timer with
 * `publishPercentileHistogram(true)` and nothing else, so Micrometer's default timer range applies:
 * its largest finite bucket is 30 s. A hold lasts minutes to 48 h, so without this filter every
 * observation would land in `+Inf` and `histogram_quantile` could only ever answer "more than 30 s".
 * The explicit SLO boundaries below are the ones the p50/p95 release-time question needs.
 */
@ApplicationScoped
class FraudHoldDurationMeterFilter {
    @Produces
    @Singleton
    fun fraudHoldDurationFilter(): MeterFilter = filter()

    companion object {
        val BUCKETS: List<Duration> = listOf(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(2),
            Duration.ofHours(4),
            Duration.ofHours(8),
            Duration.ofHours(12),
            Duration.ofHours(24),
            Duration.ofHours(36),
            Duration.ofHours(48),
        )

        private val SLO_NANOS: DoubleArray = BUCKETS.map { it.toNanos().toDouble() }.toDoubleArray()

        // The builder only takes a vararg; the copy happens once per meter registration, not per sample.
        @Suppress("SpreadOperator")
        fun filter(): MeterFilter = object : MeterFilter {
            override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
                if (id.name == DomesticPaymentWorkflowImpl.FRAUD_HOLD_DURATION_METRIC) {
                    DistributionStatisticConfig.builder()
                        .percentilesHistogram(false)
                        .serviceLevelObjectives(*SLO_NANOS)
                        .minimumExpectedValue(BUCKETS.first().toNanos().toDouble())
                        .maximumExpectedValue(BUCKETS.last().toNanos().toDouble())
                        .build()
                        .merge(config)
                } else {
                    config
                }
        }
    }
}
