// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.observability

import com.openbank.domestic.application.port.out.PaymentAdmissionMetricsPort
import com.openbank.domestic.application.port.out.PaymentHoldEnd
import com.openbank.libs.fincrime.admission.AdmissionDecision
import com.openbank.libs.fincrime.admission.AdmissionStep
import com.openbank.libs.fincrime.admission.UnavailableCause
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Admission-chain outcomes of the domestic rail (ADR-0374).
 *
 *  - `openbank_payment_admission_decisions_total{rail, decision}`: `ALLOW|CHALLENGE|HOLD|REJECT`.
 *  - `openbank_payment_admission_step_unavailable_total{rail, step, cause}`: a control that could
 *    not answer. This is the series that tells an outage from a suspicious payment.
 *  - `openbank_payment_admission_hold_ends_total{rail, end}`: how each hold ended.
 *  - `openbank_payment_admission_hold_resolutions_total{rail, decision, delivery}`: case decisions
 *    that reached the rail, and whether a run was waiting for them.
 *
 * With the chain switched off every series stays at 0, which is the honest reading: nothing was
 * admitted by it. Registered eagerly and `@Startup`, so "no holds" is a 0 and not an absent series.
 */
@Startup
@ApplicationScoped
class PaymentAdmissionMetrics : PaymentAdmissionMetricsPort {

    @Inject
    lateinit var registryInstance: Instance<MeterRegistry>

    private var decisions: Map<AdmissionDecision, Counter> = emptyMap()
    private var unavailable: Map<Pair<AdmissionStep, UnavailableCause>, Counter> = emptyMap()
    private var holdEnds: Map<PaymentHoldEnd, Counter> = emptyMap()
    private var resolutions: Map<Pair<Boolean, Boolean>, Counter> = emptyMap()

    @PostConstruct
    fun register() {
        if (registryInstance.isResolvable) bindTo(registryInstance.get())
    }

    fun bindTo(registry: MeterRegistry) {
        decisions = AdmissionDecision.entries.associateWith { decision ->
            Counter.builder(DECISIONS_METRIC).tag(RAIL_TAG, RAIL).tag("decision", decision.name).register(registry)
        }
        unavailable = AdmissionStep.entries.flatMap { step -> UnavailableCause.entries.map { step to it } }
            .associateWith { (step, cause) ->
                Counter.builder(UNAVAILABLE_METRIC)
                    .tag(RAIL_TAG, RAIL)
                    .tag("step", step.name)
                    .tag("cause", cause.name)
                    .register(registry)
            }
        holdEnds = PaymentHoldEnd.entries.associateWith { end ->
            Counter.builder(HOLD_ENDS_METRIC).tag(RAIL_TAG, RAIL).tag("end", end.name).register(registry)
        }
        resolutions = listOf(true, false).flatMap { release -> listOf(release to true, release to false) }
            .associateWith { (release, delivered) ->
                Counter.builder(RESOLUTIONS_METRIC)
                    .tag(RAIL_TAG, RAIL)
                    .tag("decision", if (release) "RELEASE" else "REJECT")
                    .tag("delivery", if (delivered) "DELIVERED" else "NOT_WAITING")
                    .register(registry)
            }
    }

    override fun recordDecision(decision: AdmissionDecision) {
        decisions[decision]?.increment()
    }

    override fun recordUnavailable(step: AdmissionStep, cause: UnavailableCause) {
        unavailable[step to cause]?.increment()
    }

    override fun recordHoldEnd(end: PaymentHoldEnd) {
        holdEnds[end]?.increment()
    }

    override fun recordResolution(release: Boolean, delivered: Boolean) {
        resolutions[release to delivered]?.increment()
    }

    companion object {
        const val DECISIONS_METRIC = "openbank.payment.admission.decisions"
        const val UNAVAILABLE_METRIC = "openbank.payment.admission.step.unavailable"
        const val HOLD_ENDS_METRIC = "openbank.payment.admission.hold.ends"
        const val RESOLUTIONS_METRIC = "openbank.payment.admission.hold.resolutions"
        private const val RAIL_TAG = "rail"
        private const val RAIL = "domestic"
    }
}
