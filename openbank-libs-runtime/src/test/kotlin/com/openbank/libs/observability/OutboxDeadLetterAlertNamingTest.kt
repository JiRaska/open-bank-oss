// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusNamingConvention
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The producer/consumer seam for the `CardIssuanceOutboxDeadLettered` alert (#4005).
 *
 * An alert whose selector names a metric, label or value the exporter does not produce fires
 * never — and a never-firing alert is indistinguishable from a healthy service, which is the exact
 * failure #4005 is about. Testing either side alone cannot see that: the gauge test asserts the
 * supplier's *value*, and nothing at all reads the PrometheusRule.
 *
 * So this test derives the series selector from a **real registration** — rendered through
 * Micrometer's own [PrometheusNamingConvention], not a hand-rolled dot-to-underscore helper, which
 * would only prove itself — and then asserts that exact string is what the committed alert rule's
 * `expr:` uses.
 *
 * Deliberately matched against the `expr:` **line**, not against the file as a whole: the rule is
 * preceded by a comment block that names `openbank_outbox_dead_lettered` three times, so a
 * whole-file `contains` would stay green with the expression deleted.
 */
class OutboxDeadLetterAlertNamingTest {

    private fun withRegistry(reg: MeterRegistry): DomainMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        every { inst.isResolvable } returns true
        every { inst.get() } returns reg
        return DomainMetrics().apply { registryInstance = inst }
    }

    /** The PrometheusRule the alert lives in, relative to this module's project dir. */
    private val rulesFile = File("../openbank-infra/gitops/components/payments/prometheus-rules.yaml")

    @Test
    fun `the alert selector is exactly what a real gauge registration exports`() {
        val reg = SimpleMeterRegistry()
        withRegistry(reg).registerOutboxDeadLettered("card-issuance") { 24L }

        val meter = reg.meters.single { it.id.name == "openbank.outbox.dead_lettered" }
        val convention = PrometheusNamingConvention()
        val series = convention.name(meter.id.name, meter.id.type, meter.id.baseUnit)
        val tag = meter.id.getTag("service")

        assertThat(series).isEqualTo("openbank_outbox_dead_lettered")
        assertThat(tag).isEqualTo("card-issuance")

        val selector = """$series{service="$tag"}"""
        assertThat(rulesFile).exists()
        val exprLines = rulesFile.readLines()
            .map { it.trim() }
            .filter { it.startsWith("expr:") }

        assertThat(exprLines)
            .describedAs(
                "CardIssuanceOutboxDeadLettered must select the series the exporter really " +
                    "produces — a selector naming a label the metric does not carry fires never, " +
                    "and reads exactly like 'no problem'",
            )
            .contains("expr: $selector > 0")
    }

    /**
     * The counter is not a substitute, and this pins why in code rather than in a comment: it
     * renders to a different series name (`_total`), so anyone who "simplifies" the alert onto
     * `openbank_outbox_dead` is not tightening the same expression — they are selecting a series
     * that does not exist until the running process dead-letters something, having lost the
     * ability to see rows that were dead-lettered before it started.
     */
    @Test
    fun `the dead counter renders to a different series and cannot back this alert`() {
        val reg = SimpleMeterRegistry()
        withRegistry(reg).outboxDead("card-issuance")

        val counter = reg.meters.single { it.id.name == "openbank.outbox.dead" }
        val convention = PrometheusNamingConvention()
        val series = convention.name(counter.id.name, counter.id.type, counter.id.baseUnit)

        // The naming convention stops at the base name; the Prometheus exporter appends `_total`
        // for a counter at scrape time, which is why the live series is `openbank_outbox_dead_total`
        // — measured 2026-08-09 to have ZERO series fleet-wide while card-issuance held 24 DEAD
        // rows, because a Micrometer counter is not created until its first increment.
        assertThat(series).isEqualTo("openbank_outbox_dead")
        assertThat(series).isNotEqualTo("openbank_outbox_dead_lettered")
        assertThat(counter.id.type).isEqualTo(io.micrometer.core.instrument.Meter.Type.COUNTER)
    }
}
