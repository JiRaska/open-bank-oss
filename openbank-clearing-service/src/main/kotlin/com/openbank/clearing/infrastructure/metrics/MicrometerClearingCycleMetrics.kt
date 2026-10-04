// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.metrics

import com.openbank.clearing.application.port.out.ClearingCycleMetrics
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `openbank_clearing_unsettleable_pending_items{currency}` (#11974): PENDING items the last
 * clearing cycle left unbatched because their currency has no settlement GL pair. A healthy value
 * is 0; anything above it is money that will never settle until the GL is seeded, which is the
 * state an operator must see. A currency that drops out of the latest count is set back to 0
 * rather than left at its last value.
 */
@ApplicationScoped
class MicrometerClearingCycleMetrics(private val registry: MeterRegistry) : ClearingCycleMetrics {

    private val byCurrency = ConcurrentHashMap<String, AtomicLong>()

    override fun recordUnsettleablePending(countsByCurrency: Map<String, Long>) {
        byCurrency.keys.filterNot(countsByCurrency::containsKey).forEach { byCurrency[it]?.set(0) }
        countsByCurrency.forEach { (currency, count) -> holder(currency).set(count) }
    }

    private fun holder(currency: String): AtomicLong = byCurrency.computeIfAbsent(currency) { ccy ->
        AtomicLong().also {
            Gauge.builder(GAUGE, it) { v -> v.get().toDouble() }
                .description("PENDING clearing items in a currency with no settlement GL account")
                .tag("currency", ccy)
                .register(registry)
        }
    }

    companion object {
        const val GAUGE = "openbank.clearing.unsettleable.pending.items"
    }
}
