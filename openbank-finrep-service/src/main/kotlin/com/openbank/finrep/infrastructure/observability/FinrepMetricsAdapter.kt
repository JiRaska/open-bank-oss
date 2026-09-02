// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.observability

import com.openbank.finrep.application.port.out.FinrepMetricsPort
import com.openbank.finrep.application.port.out.RegulatoryFramework
import com.openbank.finrep.application.port.out.TemplateFailureReason
import com.openbank.finrep.application.port.out.TemplateRender
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject

/**
 * Micrometer adapter for [FinrepMetricsPort] (ADR-0077 Tier C). Emits, all tagged
 * `service="finrep"`:
 *
 *  - `openbank_finrep_templates_rendered_total{framework,template,balanced,balance_verdict}` —
 *    render rate, and for FINREP whether the balance sheet actually balanced. `balanced="false"` is
 *    a supervisory defect, not a warning; `balanced="not_applicable"` is COREP, which defines no
 *    such identity.
 *
 *    `balance_verdict` says WHICH of the two independent sources objected (issue #6011), a bounded
 *    four-value tag from `BalanceVerdict`. The one worth alerting on separately is
 *    `sources_disagree`: it is not an accounting defect at all but an EVIDENCE defect — the lines
 *    finrep evaluated are not the lines ledger evaluated, i.e. the response was truncated,
 *    filtered or paginated between the two services. That case renders a well-formed 200 of
 *    under-reported figures and, when the survivors happen to still tie out, passes finrep's own
 *    recomputation; `trial_balance.lines` only shows a collapse to ZERO, not a partial loss.
 *    `ledger_flag_absent` is a contract change, not a books problem, and is deliberately not the
 *    same series as `agreed_imbalanced`.
 *
 *    The tag is named for what it establishes — an agreement or disagreement between two published
 *    verdicts — never "verified" or "reconciled", neither of which this service is in a position to
 *    claim about a ledger it only reads.
 *  - `openbank_finrep_template_render_duration_seconds{framework,template}` — includes the ledger
 *    trial-balance hop, which is the only I/O on the path.
 *  - `openbank_finrep_trial_balance_lines{framework,template}` — the size of the input the report was
 *    derived from. A collapse to zero renders a template of honest-looking zeros with a 200 response;
 *    this is the only series where that shows.
 *  - `openbank_finrep_template_cells{framework,template}` /
 *    `openbank_finrep_template_data_gap_cells{framework,template}` — how much of the return is real.
 *    COREP C 01.00 flags capital-structure rows as explicit gaps (ADR-0097 forbids silent ones), and
 *    the gap:cell ratio is how far the report is from being submittable.
 *  - `openbank_finrep_template_failures_total{framework,reason}` — `unknown_template` is a client
 *    error, `ledger_unavailable` is an outage that means no regulatory report can be produced at all.
 *
 * Service-local `MeterRegistry`, null-safe via [Instance] exactly like libs `DomainMetrics`: report
 * shape counters are finrep-specific, so adding them to the shared libs facade would force a
 * fleet-wide rebuild for a one-service concern.
 */
@ApplicationScoped
class FinrepMetricsAdapter(private val registry: MeterRegistry?) : FinrepMetricsPort {

    // CDI constructor: MeterRegistry is optional (absent when no Prometheus registry is on the
    // classpath, e.g. slim test slices). Without an explicit @Inject ctor, ArC sees two constructors,
    // registers no bean, and FinrepService is left with an unsatisfied dependency at build time.
    @Inject
    constructor(registryInstance: Instance<MeterRegistry>) : this(
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    override fun templateRendered(render: TemplateRender) {
        val r = registry ?: return
        val framework = render.framework.name.lowercase()
        Counter.builder("openbank.finrep.templates.rendered")
            .tag("service", SERVICE)
            .tag("framework", framework)
            .tag("template", render.templateId)
            .tag("balanced", render.balanced?.toString() ?: NOT_APPLICABLE)
            .tag("balance_verdict", render.balanceVerdict?.name?.lowercase() ?: NOT_APPLICABLE)
            .description("Rendered FINREP/COREP templates")
            .register(r)
            .increment()
        Timer.builder("openbank.finrep.template.render.duration")
            .tag("service", SERVICE)
            .tag("framework", framework)
            .tag("template", render.templateId)
            .publishPercentiles(P50, P95, P99)
            .publishPercentileHistogram()
            .description("Time to render one template, including the ledger trial-balance hop")
            .register(r)
            .record(render.duration)
        summary(r, "openbank.finrep.trial_balance.lines", framework, render.templateId)
            .record(render.trialBalanceLines.toDouble())
        summary(r, "openbank.finrep.template.cells", framework, render.templateId)
            .record(render.cells.toDouble())
        summary(r, "openbank.finrep.template.data_gap_cells", framework, render.templateId)
            .record(render.dataGapCells.toDouble())
    }

    override fun templateFailed(framework: RegulatoryFramework, reason: TemplateFailureReason) {
        registry?.let { r ->
            Counter.builder("openbank.finrep.template.failures")
                .tag("service", SERVICE)
                .tag("framework", framework.name.lowercase())
                .tag("reason", reason.name.lowercase())
                .description("Template renders that produced no report")
                .register(r)
                .increment()
        }
    }

    private fun summary(
        registry: MeterRegistry,
        name: String,
        framework: String,
        template: String,
    ): DistributionSummary = DistributionSummary.builder(name)
        .tag("service", SERVICE)
        .tag("framework", framework)
        .tag("template", template)
        .publishPercentiles(P50, P95, P99)
        .publishPercentileHistogram()
        .register(registry)

    companion object {
        private const val SERVICE = "finrep"

        /** COREP defines no balance-sheet identity, so `balanced` is neither true nor false there. */
        private const val NOT_APPLICABLE = "not_applicable"

        // The fleet-standard percentile set (libs DomainMetrics publishes the same three).
        private const val P50 = 0.5
        private const val P95 = 0.95
        private const val P99 = 0.99
    }
}
