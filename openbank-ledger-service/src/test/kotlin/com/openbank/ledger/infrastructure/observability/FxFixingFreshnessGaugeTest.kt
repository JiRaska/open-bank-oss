// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pins the three properties that make `openbank.fx.fixing.age_seconds` worth alerting on (#3921).
 *
 * Every assertion here is on the gauge's **value**, never on its presence. `isNotNull()` on an age
 * gauge passes against a holder still sitting at 1970 — the exact way an `Instant.EPOCH` default
 * survives a full test suite (the `AuditEvent.timestamp` class of bug, #3882). A freshness signal
 * that is only asserted to exist is not asserted at all.
 */
class FxFixingFreshnessGaugeTest {

    private val t0: Instant = Instant.parse("2026-05-30T15:00:00Z")
    private val registry = SimpleMeterRegistry()
    private val clock = MutableClock(t0)
    private val gauge = FxFixingFreshnessGauge(registry, clock)

    private fun age(currency: String): Double? = registry.find(FxFixingFreshnessGauge.FIXING_AGE_SECONDS)
        .tag(FxFixingFreshnessGauge.CURRENCY_TAG, currency)
        .gauge()
        ?.value()

    @Test
    fun `a currency never observed ages from registration, not from the epoch`() {
        gauge.fixingObserved("EUR", validFrom = null)

        clock.advance(Duration.ofSeconds(90))

        // 90, not ~1.77e9. An Instant.EPOCH seed would make every fresh pod fire a staleness
        // alert continuously until the next daily run — up to 24h of noise no `for:` absorbs,
        // because the condition genuinely holds (ADR-0237 point 3 boot-safety).
        assertThat(age("EUR")).isEqualTo(90.0)
    }

    @Test
    fun `the published age is the age of the fixing, not of the run that used it`() {
        gauge.fixingObserved("EUR", validFrom = t0.minus(Duration.ofDays(3)))

        // Three days exactly — a Friday fixing marking a Monday position. Every other signal
        // (workflow liveness, the posted journal, the job's own log) reports this run as healthy.
        assertThat(age("EUR")).isEqualTo(Duration.ofDays(3).seconds.toDouble())
    }

    @Test
    fun `a failed resolution keeps the age climbing rather than blanking the series`() {
        gauge.fixingObserved("USD", validFrom = t0)
        assertThat(age("USD")).isEqualTo(0.0)

        clock.advance(Duration.ofDays(2))
        gauge.fixingObserved("USD", validFrom = null)

        // The feed went quiet and the series says so. Clearing the holder — or simply not
        // reporting a failed attempt — would flat-line or drop the series at exactly the moment
        // it matters, which is "a table that stopped growing" restated as a metric.
        assertThat(age("USD")).isEqualTo(Duration.ofDays(2).seconds.toDouble())
    }

    @Test
    fun `each currency ages independently and re-observation is not a re-registration`() {
        gauge.fixingObserved("EUR", validFrom = t0.minus(Duration.ofDays(1)))
        gauge.fixingObserved("GBP", validFrom = t0)
        gauge.fixingObserved("EUR", validFrom = t0)

        assertThat(age("EUR")).isEqualTo(0.0)
        assertThat(age("GBP")).isEqualTo(0.0)
        assertThat(
            registry.find(FxFixingFreshnessGauge.FIXING_AGE_SECONDS).gauges(),
        ).hasSize(2)
    }

    /** Test clock the gauge's scrape-time supplier can be moved against. */
    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
