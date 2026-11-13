// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.observability

import com.openbank.clients.transaction.UnsettledOutcome
import com.openbank.clients.transaction.UnsettledOutcomeLabel
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

    private var counters: Map<UnsettledOutcomeLabel, Counter> = emptyMap()

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    /** Zero means no observed unsettled attempts since process start, not proof of successful bookings. */
    fun bindTo(registry: MeterRegistry) {
        counters = ALL_OUTCOMES.associateWith { outcome ->
            registry.counter(METRIC, "rail", "SWIFT", "outcome", outcome.label)
        }
    }

    fun unsettled(outcome: UnsettledOutcomeLabel) {
        counters[outcome]?.increment()
    }

    /**
     * SWIFT-only extension of the shared [UnsettledOutcome] vocabulary (#13669): outcomes of the settlement
     * re-drive and of stored data, which no other rail produces. The shared values are not repeated here.
     */
    enum class SwiftOutcome(override val label: String) : UnsettledOutcomeLabel {
        // #13686: the re-drive gave up on a message; it is no longer retried and its booking clock stays open.
        REDRIVE_EXHAUSTED("redrive-exhausted"),

        // #13686: stored data no attempt can fix (unparseable 32A value date); nothing was sent.
        BAD_DATA("bad-data"),

        // #13686: one message's re-drive failed outside settlement (e.g. a database error); the sweep went on.
        REDRIVE_ERROR("redrive-error"),

        // #13686: a re-drive's booking was proven after it lost its claim (stall past the lease). The proof is
        // recorded; this is not an unsettled booking but a signal to check any manual remediation in between.
        LATE_COMPLETION("late-completion"),
    }

    companion object {
        const val METRIC = UnsettledOutcome.METRIC

        /** Every series registered eagerly at zero: the shared values first, then the SWIFT-only ones. */
        val ALL_OUTCOMES: List<UnsettledOutcomeLabel> = UnsettledOutcome.entries + SwiftOutcome.entries
    }
}
