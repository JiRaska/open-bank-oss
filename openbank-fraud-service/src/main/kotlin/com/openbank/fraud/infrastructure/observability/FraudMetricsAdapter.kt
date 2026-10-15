// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fraud.infrastructure.observability

import com.openbank.fraud.application.port.out.FraudMetricsPort
import com.openbank.fraud.domain.model.FraudVerdict
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [FraudMetricsPort] (ADR-0084 §1). Emits
 * `openbank_fraud_scores_total{verdict,rail,service="fraud"}` — a cumulative counter of scoring
 * decisions by outcome. With the engine inert in Phase 1 every increment is ALLOW; once the surfaces
 * call the scorer in shadow mode this is the series that proves the rollout is safe before any
 * verdict is enforced. `rail` is the allow-listed scoring-rail vocabulary with `rail="unknown"` for
 * anything else — the caller-controlled request string never reaches a tag (#12920, ADR-0077).
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like libs `DomainMetrics`: a
 * fraud-verdict counter is fraud-specific, so adding it to the shared libs facade would force a
 * fleet-wide rebuild for a one-service concern — the reason ADR-0084 §1 deferred this metric.
 */
@ApplicationScoped
class FraudMetricsAdapter(private val registry: MeterRegistry?) : FraudMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and FraudScoringService is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun recordVerdict(verdict: FraudVerdict, rail: String) {
        registry?.let { r ->
            Counter.builder("openbank.fraud.scores")
                .tag("service", SERVICE)
                .tag("verdict", verdict.name)
                .tag("rail", railTag(rail))
                .register(r)
                .increment()
        }
    }

    override fun recordShadowScore(score: Double) {
        registry?.let { r ->
            DistributionSummary.builder("openbank.fraud.shadow.score")
                .tag("service", SERVICE)
                .register(r)
                .record(score)
        }
    }

    override fun recordSignalReplaySuppressed(aggregate: String) {
        registry?.let { r ->
            Counter.builder("openbank.fraud.signal.replay.suppressed")
                .tag("service", SERVICE)
                .tag("aggregate", aggregate)
                .register(r)
                .increment()
        }
    }

    override fun recordSignalMissingEventTime() {
        registry?.let { r ->
            Counter.builder("openbank.fraud.signal.missing.event.time")
                .tag("service", SERVICE)
                .register(r)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "fraud"

        /** Bucket for any rail outside [ALLOWED_RAILS] — the caller's raw string never reaches a tag. */
        private const val RAIL_UNKNOWN = "unknown"

        /**
         * The rails the scoring callers actually send (ADR-0077 Tier-C bounded tag, #12920):
         * sepa-payment (`SepaPaymentActivitiesImpl`, `rail = "SEPA"`),
         * domestic-payment (`DomesticPaymentActivitiesImpl`, `rail = "DOMESTIC"`),
         * sepa-instant (`SctInstPaymentService`, `rail = "SCT_INST"`),
         * fx (`FxService`/`FxActivitiesImpl`, `rail = "FX"`), card-processing
         * (`FraudScoringAdapter.RAIL_CARD = "CARD"`). The fraud scorer's wire vocabulary is NOT the
         * libs `PaymentRail` enum (`SEPA`, `SCT_INST`, `FX` are not enum values — the #8699 shape),
         * so this set is derived from the producers' literals, and anything else buckets to
         * [RAIL_UNKNOWN] exactly like mcp's `method="unknown"`.
         */
        private val ALLOWED_RAILS = setOf("SEPA", "DOMESTIC", "SCT_INST", "FX", "CARD")

        private fun railTag(rail: String): String = if (rail in ALLOWED_RAILS) rail else RAIL_UNKNOWN
    }
}
