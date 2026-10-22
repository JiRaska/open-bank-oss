// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.observability

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
class SwiftSettlementMetrics {
    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var counters: Map<Outcome, Counter> = emptyMap()

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    /** Zero means no observed unsettled attempts since process start, not proof of successful bookings. */
    fun bindTo(registry: MeterRegistry) {
        counters = Outcome.entries.associateWith { outcome ->
            registry.counter(METRIC, "rail", "SWIFT", "outcome", outcome.label)
        }
    }

    fun unsettled(outcome: Outcome) {
        counters[outcome]?.increment()
    }

    enum class Outcome(val label: String) {
        MISMATCH("mismatch"),

        // A FAILED response, including a replay; this label does not prove the request was replayed.
        FAILED_REPLAY("failed-replay"),
        CONFLICT("conflict"),
        UNEXPECTED_STATUS("unexpected-status"),
        TRANSPORT_ERROR("transport-error"),
    }

    companion object {
        const val METRIC = "openbank.payment.unsettled"
    }
}
