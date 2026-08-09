// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepa.infrastructure.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.util.concurrent.atomic.AtomicLong

/**
 * Makes a **synthetic** fraud verdict distinguishable from a real one (#4221 layer 2).
 *
 * `com.openbank.sepa.infrastructure.client.FraudScoringAdapter` is deliberately fail-OPEN: when
 * fraud-service is unreachable it returns `ALLOW`. That posture is correct here — the verdict is
 * observed, never enforced — but until this class existed the fallback was *invisible*: the caller
 * only logs a non-ALLOW verdict, so a scorer that was down produced exactly the same silence as a
 * stream of clean payments. `fraud_scores` held zero rows for the entire life of the deployment and
 * nothing anywhere said so.
 *
 * Two series, both tagged `service="sepa-payment"`, `rail="SEPA"`:
 *
 *  - `openbank_fraud_scoring_degraded` — **gauge**, the operational "is scoring degraded right
 *    now" question. `1` = the most recent attempt fell back to a synthetic verdict, `0` = the most
 *    recent attempt was a real answer from fraud-service, and [NEVER_ATTEMPTED] (`-1`) = this pod
 *    has not scored anything since it started.
 *  - `openbank_fraud_scoring_outcomes_total` with `result="real"` / `result="synthetic"` —
 *    **counter**, for rate and for post-hoc "how much of the day was synthetic".
 *
 * ### Why a gauge, and why -1 is a distinct value
 *
 * Micrometer does not create a counter until its first increment, and a pod restart resets it — so
 * an alert written only on the synthetic counter matches nothing at all until the first failure,
 * which is indistinguishable from a healthy fleet. Both counters are therefore registered eagerly
 * at `@PostConstruct` (present at `0.0` from the first scrape), and the standing question is
 * answered by a gauge read from state rather than by a rate over a counter.
 *
 * `-1` rather than `0` for "never attempted" is the same distinction the issue is about: a boot-time
 * `0` would claim "scoring is healthy" on a pod that has never once called fraud-service, which is
 * precisely the "no fraud" / "no scoring" conflation that let this run unnoticed.
 *
 * `@Startup` because `@ApplicationScoped` is lazy: without it the bean — and therefore the gauge —
 * would not exist until the first payment, so a scrape taken before any traffic would show no series
 * at all rather than `-1`.
 */
@Startup
@ApplicationScoped
class FraudScoringMetrics {

    // Field injection of Instance<MeterRegistry>: a nullable constructor parameter would need a
    // second @Inject constructor (ArC registers no bean when it sees two plain constructors), and
    // the registry is genuinely absent in slim test slices.
    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private val degraded = AtomicLong(NEVER_ATTEMPTED)
    private var realOutcomes: Counter? = null
    private var syntheticOutcomes: Counter? = null

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    /**
     * Bind the meters to [registry]. Called once at startup by [register]; exposed so a test can
     * bind a real Prometheus registry and assert the rendered series name and label set, rather
     * than assuming how Micrometer maps them.
     */
    fun bindTo(registry: MeterRegistry) {
        Gauge.builder(DEGRADED_METRIC, degraded) { it.get().toDouble() }
            .tag("service", SERVICE)
            .tag("rail", RAIL)
            .description(
                "1 = the last fraud-scoring attempt returned a synthetic fallback verdict, " +
                    "0 = it returned a real verdict, -1 = nothing scored since this pod started",
            )
            .strongReference(true)
            .register(registry)
        realOutcomes = counter(registry, RESULT_REAL)
        syntheticOutcomes = counter(registry, RESULT_SYNTHETIC)
    }

    private fun counter(registry: MeterRegistry, result: String): Counter = Counter.builder(OUTCOMES_METRIC)
        .tag("service", SERVICE)
        .tag("rail", RAIL)
        .tag("result", result)
        .description("Fraud-scoring outcomes, split by whether the verdict came from fraud-service")
        .register(registry)

    /** A verdict that fraud-service actually produced. */
    fun recordReal() {
        realOutcomes?.increment()
        degraded.set(0)
    }

    /** A verdict this adapter invented because fraud-service could not be reached. */
    fun recordSynthetic() {
        syntheticOutcomes?.increment()
        degraded.set(1)
    }

    /** Current gauge value; for tests and for callers that want to log the state. */
    fun degradedValue(): Long = degraded.get()

    companion object {
        const val DEGRADED_METRIC = "openbank.fraud.scoring.degraded"
        const val OUTCOMES_METRIC = "openbank.fraud.scoring.outcomes"
        const val RESULT_REAL = "real"
        const val RESULT_SYNTHETIC = "synthetic"
        const val SERVICE = "sepa-payment"
        const val RAIL = "SEPA"

        /** Gauge value on a pod that has not scored anything yet — never conflated with a healthy 0. */
        const val NEVER_ATTEMPTED = -1L
    }
}
