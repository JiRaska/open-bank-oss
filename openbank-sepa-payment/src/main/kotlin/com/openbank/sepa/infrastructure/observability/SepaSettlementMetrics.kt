// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.observability

import com.openbank.clients.transaction.UnsettledOutcome
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/** Attempts with no proven booking; no payment identity or provider text is a metric label. */
@Startup
@ApplicationScoped
class SepaSettlementMetrics {
    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var counters: Map<UnsettledOutcome, Counter> = emptyMap()

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    /** Zero means no observed unsettled attempts since process start, not proof of successful bookings. */
    fun bindTo(registry: MeterRegistry) {
        counters = UnsettledOutcome.entries.associateWith { outcome ->
            registry.counter(METRIC, "rail", "SEPA", "outcome", outcome.label)
        }
    }

    fun unsettled(outcome: UnsettledOutcome) {
        counters[outcome]?.increment()
    }

    companion object {
        const val METRIC = UnsettledOutcome.METRIC
    }
}
