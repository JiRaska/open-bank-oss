// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.observability

import com.openbank.vop.application.port.out.VopRateLimitOutcome
import com.openbank.vop.application.port.out.VopRoute
import com.openbank.vop.domain.model.VopNoDataReason
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class VopMetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = VopMetricsAdapter(registry)
    private val verifiedAt = Instant.parse("2026-07-25T10:00:00Z")

    @Test
    fun `a NO_DATA verification publishes both the outcome counter and the reason counter`() {
        adapter.verificationCompleted(
            VopRoute.EXTERNAL,
            VopVerification(
                outcome = VopOutcome.NO_DATA,
                noDataReason = VopNoDataReason.NO_SCHEME_CONNECTIVITY,
                verifiedAt = verifiedAt,
            ),
            Duration.ofMillis(42),
        )

        assertThat(
            registry.get("openbank.vop.verifications")
                .tag("service", "vop").tag("route", "external").tag("outcome", "NO_DATA")
                .counter().count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.vop.no_data")
                .tag("route", "external").tag("reason", "no_scheme_connectivity")
                .counter().count(),
        ).isEqualTo(1.0)
        assertThat(
            registry.get("openbank.vop.verification.duration").tag("route", "external").timer().count(),
        ).isEqualTo(1L)
    }

    @Test
    fun `a non-NO_DATA outcome publishes no reason series`() {
        adapter.verificationCompleted(
            VopRoute.DOMESTIC,
            VopVerification(outcome = VopOutcome.MATCH, verifiedAt = verifiedAt),
            Duration.ofMillis(7),
        )

        assertThat(registry.find("openbank.vop.no_data").counters()).isEmpty()
    }

    @Test
    fun `each rate-limit outcome gets its own lower-cased tag value`() {
        adapter.rateLimitDecision(VopRateLimitOutcome.ALLOWED)
        adapter.rateLimitDecision(VopRateLimitOutcome.STORE_UNAVAILABLE)
        adapter.rateLimitDecision(VopRateLimitOutcome.STORE_UNAVAILABLE)

        assertThat(decisions("allowed")).isEqualTo(1.0)
        assertThat(decisions("store_unavailable")).isEqualTo(2.0)
    }

    @Test
    fun `is a silent no-op when no meter registry is resolvable`() {
        // Slim slices without a Prometheus registry must not crash a verification.
        val noRegistry = VopMetricsAdapter(null)

        noRegistry.verificationCompleted(
            VopRoute.DOMESTIC,
            VopVerification(outcome = VopOutcome.MATCH, verifiedAt = verifiedAt),
            Duration.ZERO,
        )
        noRegistry.rateLimitDecision(VopRateLimitOutcome.THROTTLED)
    }

    private fun decisions(outcome: String): Double = registry.get("openbank.vop.rate_limit.decisions")
        .tag("service", "vop")
        .tag("outcome", outcome)
        .counter().count()
}
