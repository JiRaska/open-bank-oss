// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.infrastructure.observability

import com.openbank.incentive.application.IncentiveMetrics
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class IncentiveMetricsAdapter(private val registry: MeterRegistry) : IncentiveMetrics {
    override fun offerPublished() {
        registry.counter("openbank.incentive.offers.published").increment()
    }
}
