// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudRejectCause
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Fraud-gate outcomes for the domestic rail (#4403, ADR-0084 §4.2).
 *
 *  - `openbank_fraud_enforcement_decisions_total{decision, mode}`: `decision` is
 *    `PROCEED|HOLD|REJECT` (what the table answered), `mode` is `shadow|enforced`. In shadow mode the
 *    decision is computed and counted but not acted on, which is the calibration signal the risk
 *    team reads before the enforcement flag is turned on.
 *  - `openbank_fraud_enforcement_rejections_total{cause}`: `DECLINED|REVIEW_REJECTED|HOLD_TIMED_OUT`.
 *
 * Every series is registered eagerly (present at 0 from the first scrape) and the bean is
 * `@Startup`, for the same reasons as [FraudScoringMetrics].
 */
@Startup
@ApplicationScoped
class FraudEnforcementMetrics : FraudEnforcementMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var decisions: Map<Pair<FraudGateAction, Boolean>, Counter> = emptyMap()
    private var rejections: Map<FraudRejectCause, Counter> = emptyMap()

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    fun bindTo(registry: MeterRegistry) {
        decisions = FraudGateAction.entries.flatMap { d -> listOf(d to true, d to false) }
            .associateWith { (decision, enforced) ->
                Counter.builder(DECISIONS_METRIC)
                    .tag("service", SERVICE)
                    .tag("decision", decision.name)
                    .tag("mode", if (enforced) MODE_ENFORCED else MODE_SHADOW)
                    .description("Fraud-gate decisions; mode=shadow means computed but not acted on")
                    .register(registry)
            }
        rejections = FraudRejectCause.entries.associateWith { cause ->
            Counter.builder(REJECTIONS_METRIC)
                .tag("service", SERVICE)
                .tag("cause", cause.name)
                .description("Payments rejected by the fraud gate, by cause")
                .register(registry)
        }
    }

    override fun recordDecision(decision: FraudGateAction, enforced: Boolean) {
        decisions[decision to enforced]?.increment()
    }

    override fun recordRejection(cause: FraudRejectCause) {
        rejections[cause]?.increment()
    }

    companion object {
        const val DECISIONS_METRIC = "openbank.fraud.enforcement.decisions"
        const val REJECTIONS_METRIC = "openbank.fraud.enforcement.rejections"
        const val MODE_SHADOW = "shadow"
        const val MODE_ENFORCED = "enforced"
        const val SERVICE = "domestic"
    }
}
