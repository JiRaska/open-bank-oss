// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.observability

import com.openbank.anacredit.application.port.out.AnaCreditMetricsPort
import com.openbank.anacredit.application.port.out.LoanStageEventOutcome
import com.openbank.anacredit.domain.model.InstrumentType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration

/**
 * Micrometer adapter for [AnaCreditMetricsPort] (ADR-0077 Tier C). Emits, all tagged
 * `service="anacredit"`:
 *
 *  - `openbank_anacredit_exposures_registered_total{instrument_type,currency,defaulted}` — intake
 *    rate. A flat line during a reporting month means the feed is being assembled from stale data.
 *  - `openbank_anacredit_return_build_duration_seconds{}` — render latency of the credit dataset.
 *  - `openbank_anacredit_return_records{}` / `openbank_anacredit_return_exclusions{}` — the size and
 *    the drop count of the last rendered return, as histograms. Under-reporting shows up here and
 *    nowhere else: the return is still valid JSON when it is missing half the book.
 *  - `openbank_anacredit_loan_stage_events_total{outcome}` — the lending-event consumer's outcome
 *    mix. `outcome=parse_error|malformed|apply_error` is the acked-and-dropped population that the
 *    poison-pill guard deliberately swallows.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like libs `DomainMetrics`: a
 * regulatory-return shape counter is AnaCredit-specific, so adding it to the shared libs facade
 * would force a fleet-wide rebuild for a one-service concern.
 */
@ApplicationScoped
class AnaCreditMetricsAdapter(private val registry: MeterRegistry?) : AnaCreditMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and AnaCreditService is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun exposureRegistered(instrumentType: InstrumentType, currency: String, defaulted: Boolean) {
        registry?.let { r ->
            Counter.builder("openbank.anacredit.exposures.registered")
                .tag("service", SERVICE)
                .tag("instrument_type", instrumentType.name)
                .tag("currency", currency)
                .tag("defaulted", defaulted.toString())
                .description("AnaCredit credit exposures registered or replaced")
                .register(r)
                .increment()
        }
    }

    override fun returnBuilt(recordCount: Int, exclusionCount: Int, duration: Duration) {
        registry?.let { r ->
            Timer.builder("openbank.anacredit.return.build.duration")
                .tag("service", SERVICE)
                .publishPercentiles(P50, P95, P99)
                .publishPercentileHistogram()
                .description("Time to render one AnaCredit credit-dataset return")
                .register(r)
                .record(duration)
            summary(r, "openbank.anacredit.return.records", "Rows in a rendered AnaCredit return")
                .record(recordCount.toDouble())
            summary(
                r,
                "openbank.anacredit.return.exclusions",
                "Instruments dropped by the AnaCredit eligibility policy",
            ).record(exclusionCount.toDouble())
        }
    }

    override fun loanStageEvent(outcome: LoanStageEventOutcome) {
        registry?.let { r ->
            Counter.builder("openbank.anacredit.loan_stage.events")
                .tag("service", SERVICE)
                .tag("outcome", outcome.name.lowercase())
                .description("Consumed lending loan.stage_changed events by outcome")
                .register(r)
                .increment()
        }
    }

    private fun summary(registry: MeterRegistry, name: String, description: String): DistributionSummary =
        DistributionSummary.builder(name)
            .tag("service", SERVICE)
            .publishPercentiles(P50, P95, P99)
            .publishPercentileHistogram()
            .description(description)
            .register(registry)

    companion object {
        private const val SERVICE = "anacredit"

        // The fleet-standard percentile set (libs DomainMetrics publishes the same three).
        private const val P50 = 0.5
        private const val P95 = 0.95
        private const val P99 = 0.99
    }
}
