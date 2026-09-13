// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.infrastructure.observability

import com.openbank.loyalty.application.port.out.LoyaltyMetricsPort
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters named for what they can actually establish.
 *
 * `openbank_loyalty_earn_capped_total` is the one to alert on. A loyalty programme that has
 * stopped awarding because every party is at the annual cap looks, from every success metric,
 * exactly like a quiet week. The platform has already shipped the version of this mistake where a
 * disabled adapter counted as a delivery, so the refusal gets its own counter and its own name.
 *
 * `openbank_loyalty_outstanding_leaves` is a gauge over the provisioning obligation. It is seeded
 * at registration from the [AtomicLong]'s zero and updated by the daily summary — a boot-time
 * reading of 0 means "not yet computed", which is a fourth state next to healthy, degraded and
 * absent, so any alert over it must exclude a pod that has not run a summary yet.
 */
@ApplicationScoped
class LoyaltyMetricsAdapter(registry: MeterRegistry) : LoyaltyMetricsPort {

    private val outstanding = AtomicLong(0)
    private val meterRegistry = registry

    init {
        registry.gauge("openbank_loyalty_outstanding_leaves", outstanding) { it.get().toDouble() }
    }

    override fun earnAwarded(sourceId: String, leaves: Int) {
        meterRegistry.counter("openbank_loyalty_earn_awarded_total", "source", sourceId).increment()
        meterRegistry.counter("openbank_loyalty_leaves_awarded_total", "source", sourceId)
            .increment(leaves.toDouble())
    }

    override fun earnCapped(sourceId: String, requested: Int) {
        meterRegistry.counter("openbank_loyalty_earn_capped_total", "source", sourceId).increment()
    }

    override fun earnReplayed(sourceId: String) {
        meterRegistry.counter("openbank_loyalty_earn_replayed_total", "source", sourceId).increment()
    }

    override fun benefitGranted(benefitId: String, price: Int) {
        meterRegistry.counter("openbank_loyalty_benefit_granted_total", "benefit", benefitId).increment()
        meterRegistry.counter("openbank_loyalty_leaves_burned_total", "benefit", benefitId)
            .increment(price.toDouble())
    }

    override fun benefitRefused(benefitId: String, reason: String) {
        meterRegistry.counter("openbank_loyalty_benefit_refused_total", "benefit", benefitId, "reason", reason)
            .increment()
    }

    override fun leavesExpired(count: Int) {
        meterRegistry.counter("openbank_loyalty_lots_expired_total").increment(count.toDouble())
    }

    override fun outstandingObligation(leaves: Long) {
        outstanding.set(leaves)
    }
}
