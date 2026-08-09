// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.observability

import com.openbank.ledger.application.port.out.FxFixingFreshnessPort
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Micrometer adapter for [FxFixingFreshnessPort]: publishes [FIXING_AGE_SECONDS] tagged by
 * `currency` — the scrape-time age of the ČNB fixing the FX revaluation last used for that
 * currency (#3921).
 *
 * ### Two design choices that are the whole value of the gauge
 *
 * **Seeded at registration, not at [Instant.EPOCH].** A fresh pod that has not yet run a
 * revaluation publishes an age counted from its own start, so it reads as old as the pod rather
 * than as decades. That is ADR-0237 point 3's boot-safety property, and it is what makes this
 * gauge safe to alert on with a plain threshold: an EPOCH seed makes every deploy fire the alert
 * continuously until the next successful run, which for a daily job is up to 24h of noise that no
 * `for:` duration can absorb, because the condition genuinely persists. The cost of the
 * registration seed is that a pod restarted more often than the feed publishes can mask staleness;
 * the daily job's 2-day threshold is far longer than the pod's restart cadence, so a mask requires
 * a restart storm, which has its own alerts.
 *
 * **A resolution failure does not clear the holder.** [fixingObserved] with a `null` instant leaves
 * the last known fixing time in place, so the published age keeps climbing. Clearing it (or simply
 * not publishing) would make the series flat-line or disappear at exactly the moment the feed
 * stopped delivering — the "table that stopped growing" failure, restated as a metric.
 *
 * Registration is lazy per currency and idempotent: [ConcurrentHashMap.computeIfAbsent] registers
 * the gauge once on first sight, matching `DomainMetrics.recordReconciliationDrift`'s shape for the
 * same reason (the tag set is only known once the workload runs).
 */
@ApplicationScoped
class FxFixingFreshnessGauge(private val registry: MeterRegistry, private val clock: Clock) :
    FxFixingFreshnessPort {

    private val lastFixing = ConcurrentHashMap<String, AtomicReference<Instant>>()

    override fun fixingObserved(currency: String, validFrom: Instant?) {
        val holder = lastFixing.computeIfAbsent(currency) { code ->
            val ref = AtomicReference(clock.instant())
            Gauge.builder(FIXING_AGE_SECONDS) { Duration.between(ref.get(), clock.instant()).seconds.toDouble() }
                .tag(CURRENCY_TAG, code)
                .strongReference(true)
                .register(registry)
            ref
        }
        if (validFrom != null) holder.set(validFrom)
    }

    companion object {
        /**
         * Meter name (Micrometer, dotted). Prometheus scrapes it as
         * `openbank_fx_fixing_age_seconds` — the series
         * `openbank-infra/gitops/components/observability/prometheus-rules-fx-fixing.yaml` alerts
         * on. Written down once here rather than spelled at both ends, the #2187 lesson: the
         * producer and the consumer each hardcoding their own literal is how a metric ends up
         * emitted under a name nothing queries, and both sides' tests still pass.
         */
        const val FIXING_AGE_SECONDS: String = "openbank.fx.fixing.age_seconds"

        /** Tag carrying the position currency — ISO-4217, three values in ADR-0046 scope. */
        const val CURRENCY_TAG: String = "currency"
    }
}
