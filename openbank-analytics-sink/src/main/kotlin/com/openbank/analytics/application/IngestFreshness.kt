// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Ingest freshness / lag telemetry for DORA monitoring and RPO alerting (ADR-0023, F8).
 *
 * The analytics layer is eventually consistent, which is acceptable for reporting only if the
 * *staleness is observable*. This bean tracks the lag of the last ingested event (now − occurredAt),
 * the timestamp of the last successful ingest, and a running dead-letter count, and exposes them as
 * Micrometer gauges so alerts can fire when lag exceeds the agreed RPO or the DLQ starts filling.
 *
 * The same values back the [com.openbank.analytics.infrastructure.health.IngestHealthCheck] readiness
 * probe, so a stalled or badly-lagging sink is visible to both Prometheus and the platform.
 */
@ApplicationScoped
class IngestFreshness {

    @Inject lateinit var registry: MeterRegistry

    @Inject lateinit var clock: Clock

    /** Lag in seconds of the last ingested event; -1 until the first event is seen. */
    private val lagSeconds = AtomicLong(-1)

    /** Epoch millis of the last successful ingest; 0 until the first event. */
    private val lastIngestEpochMs = AtomicLong(0)
    private val deadLetterCount = AtomicLong(0)

    @PostConstruct
    fun bindGauges() {
        registry.gauge("openbank_analytics_ingest_lag_seconds", lagSeconds) { it.get().toDouble() }
        registry.gauge("openbank_analytics_last_ingest_epoch_ms", lastIngestEpochMs) { it.get().toDouble() }
        registry.gauge("openbank_analytics_dead_letter_total", deadLetterCount) { it.get().toDouble() }
    }

    fun recordIngest(occurredAt: Instant) {
        val now = Instant.now(clock)
        lagSeconds.set((now.epochSecond - occurredAt.epochSecond).coerceAtLeast(0))
        lastIngestEpochMs.set(now.toEpochMilli())
    }

    fun recordDeadLetter() {
        deadLetterCount.incrementAndGet()
    }

    fun currentLagSeconds(): Long = lagSeconds.get()
    fun lastIngestEpochMs(): Long = lastIngestEpochMs.get()
    fun deadLetters(): Long = deadLetterCount.get()
}
