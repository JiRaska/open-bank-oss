// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.libs.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Turns on Prometheus **native histogram buckets** for the HTTP server timer and the
 * domain payment-processing timer (ADR-0082, metric→trace exemplars).
 *
 * WHY: Prometheus *exemplars* (the one-click jump from a latency histogram point to the
 * exact trace that produced it) can only attach to a `*_bucket` series. By default
 * Quarkus Micrometer publishes only the summary count/sum, so there is no bucket series
 * for an exemplar to hang on and `traces_exemplars` stays empty. This filter enables the
 * per-bucket histogram on the meters that matter.
 *
 * The exemplar VALUES are wired automatically by `quarkus-micrometer-registry-prometheus`
 * + `quarkus-opentelemetry` (the active OTel span context is sampled into the bucket) when
 * Prometheus scrapes `/q/metrics` in OpenMetrics format — which it does by default. This
 * filter only turns on the buckets those exemplars decorate.
 *
 * Quarkus auto-discovers every CDI `MeterFilter` bean and applies it to the registry, so
 * pulling `openbank-libs` onto a service is enough — no per-service wiring.
 */
@ApplicationScoped
class HistogramMeterFilters {
    @Produces
    @Singleton
    fun exemplarHistogramFilter(): MeterFilter = object : MeterFilter {
        private val histogramMeters = setOf(
            "http.server.requests",
            "openbank.payment.processing.duration",
        )

        override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
            if (id.name in histogramMeters) {
                DistributionStatisticConfig.builder()
                    .percentilesHistogram(true)
                    .build()
                    .merge(config)
            } else {
                config
            }
    }
}
