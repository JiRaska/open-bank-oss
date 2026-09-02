// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.infrastructure.observability

import com.openbank.finrep.application.port.out.RegulatoryFramework
import com.openbank.finrep.application.port.out.TemplateFailureReason
import com.openbank.finrep.application.port.out.TemplateRender
import com.openbank.finrep.domain.model.BalanceVerdict
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class FinrepMetricsAdapterTest {

    private val registry = SimpleMeterRegistry()
    private val adapter = FinrepMetricsAdapter(registry)

    @Test
    fun `an unbalanced FINREP render is tagged balanced=false`() {
        // A balance sheet that does not balance is a supervisory defect; `isBalanced` was previously
        // computed, serialised and never looked at.
        adapter.templateRendered(render(balanced = false))

        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("service", "finrep").tag("framework", "finrep").tag("template", "F01.01")
                .tag("balanced", "false")
                .counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a null balanced flag renders as not_applicable, never as true`() {
        adapter.templateRendered(
            render(
                framework = RegulatoryFramework.COREP,
                template = "C_01.00",
                balanced = null,
                balanceVerdict = null,
            ),
        )

        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("framework", "corep").tag("balanced", "not_applicable").counter().count(),
        ).isEqualTo(1.0)
        assertThat(registry.find("openbank.finrep.templates.rendered").tag("balanced", "true").counters()).isEmpty()
        // Same rule for the verdict tag (issue #6011): COREP has no second source to agree with, so
        // it must not borrow one of the four FINREP verdict values.
        assertThat(
            registry.get("openbank.finrep.templates.rendered")
                .tag("framework", "corep").tag("balance_verdict", "not_applicable").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `each balance verdict renders as its own tag value`() {
        // Issue #6011: the point of the verdict is that "the two sources disagree" is a DIFFERENT
        // series from "both agree it does not balance". If they shared a tag value, the truncation
        // case would be unalertable — indistinguishable from an ordinary ledger imbalance.
        BalanceVerdict.entries.forEachIndexed { i, verdict ->
            adapter.templateRendered(render(template = "F01.0$i", balanced = false, balanceVerdict = verdict))
        }

        assertThat(
            BalanceVerdict.entries.map { verdict ->
                registry.get("openbank.finrep.templates.rendered")
                    .tag("balance_verdict", verdict.name.lowercase()).counters().size
            },
        ).allMatch { it == 1 }
    }

    @Test
    fun `a render publishes duration, input lines, cells and data-gap cells`() {
        adapter.templateRendered(render(trialBalanceLines = 12, cells = 30, dataGapCells = 4))

        assertThat(registry.get("openbank.finrep.template.render.duration").timer().count()).isEqualTo(1L)
        assertThat(registry.get("openbank.finrep.trial_balance.lines").summary().totalAmount()).isEqualTo(12.0)
        assertThat(registry.get("openbank.finrep.template.cells").summary().totalAmount()).isEqualTo(30.0)
        assertThat(registry.get("openbank.finrep.template.data_gap_cells").summary().totalAmount()).isEqualTo(4.0)
    }

    @Test
    fun `a failure counter carries the framework and reason but never a template tag`() {
        adapter.templateFailed(RegulatoryFramework.FINREP, TemplateFailureReason.UNKNOWN_TEMPLATE)
        adapter.templateFailed(RegulatoryFramework.COREP, TemplateFailureReason.LEDGER_UNAVAILABLE)

        val unknown = registry.get("openbank.finrep.template.failures")
            .tag("framework", "finrep").tag("reason", "unknown_template").counter()
        assertThat(unknown.count()).isEqualTo(1.0)
        // The id is caller-supplied, so it must never become a label.
        assertThat(unknown.id.tags.map { it.key }).containsExactlyInAnyOrder("service", "framework", "reason")
        assertThat(
            registry.get("openbank.finrep.template.failures")
                .tag("framework", "corep").tag("reason", "ledger_unavailable").counter().count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `is a silent no-op when no meter registry is resolvable`() {
        // Slim slices without a Prometheus registry must not crash a render.
        val noRegistry = FinrepMetricsAdapter(null)

        noRegistry.templateRendered(render())
        noRegistry.templateFailed(RegulatoryFramework.FINREP, TemplateFailureReason.UNKNOWN_TEMPLATE)
    }

    private fun render(
        framework: RegulatoryFramework = RegulatoryFramework.FINREP,
        template: String = "F01.01",
        trialBalanceLines: Int = 5,
        cells: Int = 20,
        dataGapCells: Int = 0,
        balanced: Boolean? = true,
        balanceVerdict: BalanceVerdict? = BalanceVerdict.AGREED_BALANCED,
    ) = TemplateRender(
        framework = framework,
        templateId = template,
        trialBalanceLines = trialBalanceLines,
        cells = cells,
        dataGapCells = dataGapCells,
        balanced = balanced,
        balanceVerdict = balanceVerdict,
        duration = Duration.ofMillis(33),
    )
}
