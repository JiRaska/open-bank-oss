// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.infrastructure.metrics

import com.openbank.swift.application.port.out.InstitutionTransferMetrics
import com.openbank.swift.application.port.out.ReconciliationCause
import com.openbank.swift.application.port.out.ResolutionOutcome
import com.openbank.swift.domain.institution.InstitutionTransferRail
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped

/**
 * `openbank_swift_institution_transfer_manual_reconciliation_total{rail,cause}` — the series the
 * `SwiftInstitutionTransferManualReconciliation` alert fires on (payments/prometheus-rules.yaml).
 *
 * Every (rail, cause) series is registered at 0 at BOOT (`@Startup`; an `@ApplicationScoped`
 * bean is otherwise built on first use): `increase()` cannot see the first increment of a series
 * that did not exist before it, so a lazily created counter would hide the very first stop.
 */
@Startup
@ApplicationScoped
class MicrometerInstitutionTransferMetrics(private val registry: MeterRegistry) : InstitutionTransferMetrics {
    private val counters: Map<Pair<InstitutionTransferRail, ReconciliationCause>, Counter> =
        InstitutionTransferRail.entries.flatMap { rail ->
            ReconciliationCause.entries.map { cause ->
                (rail to cause) to Counter.builder(NAME)
                    .description("Bank-own transfers that stopped in MANUAL_RECONCILIATION")
                    .tag("rail", rail.name)
                    .tag("cause", cause.name)
                    .register(registry)
            }
        }.toMap()

    private val outcomes: Map<ResolutionOutcome, Counter> = ResolutionOutcome.entries.associateWith { outcome ->
        Counter.builder(RESOLUTION)
            .description("Due bank-own transfers handled by the resolution sweeper, by outcome")
            .tag("outcome", outcome.name)
            .register(registry)
    }

    override fun manualReconciliation(rail: InstitutionTransferRail, cause: ReconciliationCause) {
        counters.getValue(rail to cause).increment()
    }

    override fun resolution(outcome: ResolutionOutcome) {
        outcomes.getValue(outcome).increment()
    }

    companion object {
        const val NAME = "openbank.swift.institution_transfer.manual_reconciliation"
        const val RESOLUTION = "openbank.swift.institution_transfer.resolution"
    }
}
