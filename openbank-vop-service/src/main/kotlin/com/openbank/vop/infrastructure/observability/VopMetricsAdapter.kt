// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.infrastructure.observability

import com.openbank.vop.application.port.out.VopMetricsPort
import com.openbank.vop.application.port.out.VopRateLimitOutcome
import com.openbank.vop.application.port.out.VopRoute
import com.openbank.vop.domain.model.VopOutcome
import com.openbank.vop.domain.model.VopVerification
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration

/**
 * Micrometer adapter for [VopMetricsPort] (ADR-0077 Tier C). Emits, all tagged `service="vop"`:
 *
 *  - `openbank_vop_verifications_total{route,outcome}` — the verification mix. The MATCH:NO_MATCH
 *    ratio is also the anti-enumeration signal: a caller driving NO_MATCH is guessing names.
 *  - `openbank_vop_no_data_total{route,reason}` — why we could not answer. `reason=lookup_unavailable`
 *    is a live account-service/party-service outage that VoP is deliberately hiding from the payer
 *    by failing open (ADR-0171 §3); nothing else distinguishes it from normal traffic.
 *  - `openbank_vop_verification_duration_seconds{route}` — the latency the payer waits, on the
 *    payment-initiation path (IPR Art. 5c).
 *  - `openbank_vop_rate_limit_decisions_total{outcome}` — the enumeration control's own outcomes.
 *    `throttled` is enumeration pressure; `store_unavailable` is the fail-closed Valkey path, which
 *    rejects every requester and previously produced nothing but an ERROR log line.
 *
 * The NO_DATA reason is a **second series**, not a tag on `verifications_total`: it is populated for
 * exactly one of the four outcomes, so folding it in would put a `reason="none"` label on every
 * MATCH and make the common query carry a label it never varies over.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like libs `DomainMetrics`: a VoP
 * outcome counter is VoP-specific, so adding it to the shared libs facade would force a fleet-wide
 * rebuild for a one-service concern.
 */
@ApplicationScoped
class VopMetricsAdapter(private val registry: MeterRegistry?) : VopMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and VopVerificationService is left with an unsatisfied dependency at build
    // time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun verificationCompleted(route: VopRoute, verification: VopVerification, duration: Duration) {
        val r = registry ?: return
        Counter.builder("openbank.vop.verifications")
            .tag("service", SERVICE)
            .tag("route", route.name.lowercase())
            .tag("outcome", verification.outcome.name)
            .description("Completed Verification-of-Payee checks by route and outcome")
            .register(r)
            .increment()
        if (verification.outcome == VopOutcome.NO_DATA) {
            Counter.builder("openbank.vop.no_data")
                .tag("service", SERVICE)
                .tag("route", route.name.lowercase())
                .tag("reason", verification.noDataReason?.name?.lowercase() ?: "unspecified")
                .description("Verifications that could not be answered, by reason")
                .register(r)
                .increment()
        }
        Timer.builder("openbank.vop.verification.duration")
            .tag("service", SERVICE)
            .tag("route", route.name.lowercase())
            .publishPercentiles(P50, P95, P99)
            .publishPercentileHistogram()
            .description("End-to-end Verification-of-Payee latency, including the evidence write")
            .register(r)
            .record(duration)
    }

    override fun rateLimitDecision(outcome: VopRateLimitOutcome) {
        registry?.let { r ->
            Counter.builder("openbank.vop.rate_limit.decisions")
                .tag("service", SERVICE)
                .tag("outcome", outcome.name.lowercase())
                .description("Per-requester rate-limit decisions on the VoP verify endpoint")
                .register(r)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "vop"

        // The fleet-standard percentile set (libs DomainMetrics publishes the same three).
        private const val P50 = 0.5
        private const val P95 = 0.95
        private const val P99 = 0.99
    }
}
