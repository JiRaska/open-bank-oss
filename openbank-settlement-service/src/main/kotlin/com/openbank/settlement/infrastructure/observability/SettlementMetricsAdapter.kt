// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.observability

import com.openbank.settlement.application.port.out.OriginateOutcome
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.WorkflowStartOutcome
import com.openbank.settlement.domain.model.SettlementStatus
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [SettlementMetricsPort]. Emits three counters, all tagged
 * `service="settlement"`:
 *
 *  * `openbank_settlement_transitions_total{status}` — every saga state transition
 *  * `openbank_settlement_originations_total{outcome}` — CREATED / IDEMPOTENT_HIT / CONCURRENT_RACE
 *  * `openbank_settlement_workflow_starts_total{outcome}` — STARTED / ALREADY_RUNNING / NOT_STARTED_TERMINAL
 *
 * Name them for what they establish. `transitions_total{status="BOOKED"}` is what the service
 * observed itself commit — not "money settled", which is a claim only the ledger can make. The
 * distinction is the one the push-notification adapter got wrong by calling an accepted request a
 * delivery (ADR-0252 phase 0).
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like `FraudMetricsAdapter`: these
 * are settlement-specific series, so putting them in the shared libs facade would force a
 * fleet-wide rebuild for a one-service concern. The explicit `@Inject` constructor is required —
 * without it ArC sees two constructors, registers no bean, and every injection point of this class
 * is unsatisfied at build time.
 */
@ApplicationScoped
class SettlementMetricsAdapter(private val registry: MeterRegistry?) : SettlementMetricsPort {

    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun recordTransition(status: SettlementStatus) =
        increment("openbank.settlement.transitions", "status", status.name)

    override fun recordOriginated(outcome: OriginateOutcome) =
        increment("openbank.settlement.originations", "outcome", outcome.name)

    override fun recordWorkflowStart(outcome: WorkflowStartOutcome) =
        increment("openbank.settlement.workflow.starts", "outcome", outcome.name)

    private fun increment(name: String, tag: String, value: String) {
        registry?.let { r ->
            Counter.builder(name)
                .tag("service", SERVICE)
                .tag(tag, value)
                .register(r)
                .increment()
        }
    }

    companion object {
        private const val SERVICE = "settlement"
    }
}
