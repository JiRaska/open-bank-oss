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
 * The producer/consumer seam for every `*OutboxDeadLettered` alert (#4005 card-issuance,
 * #4701 billing).
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

    /**
     * Every service that binds [AbstractOutboxDeadLetterGauge], paired with the PrometheusRule its
     * alert lives in (paths relative to this module's project dir).
     *
     * This is a hand-kept list of the thing it checks, which is normally the shape that reads as
     * *passing* when it is short rather than as *unchecked* — so it is deliberately paired with
     * [every dead-letter gauge binding is covered here] below, which derives the real set from the
     * source tree and fails when a service adds a gauge without adding its row.
     */
    private val alertRules = mapOf(
        "card-issuance" to File("../openbank-infra/gitops/components/payments/prometheus-rules.yaml"),
        "billing" to File("../openbank-infra/gitops/components/billing/prometheus-rules-billing.yaml"),
        // ADR-0283 phase 1: the card money path binds the same gauge, and its DEAD rows are
        // money that moved with nobody told. Same file as card-issuance — both are payments.
        "card-processing" to File("../openbank-infra/gitops/components/payments/prometheus-rules.yaml"),
    )

    @Test
    fun `the alert selector is exactly what a real gauge registration exports`() {
        assertThat(alertRules).isNotEmpty()

        alertRules.forEach { (serviceTag, rulesFile) ->
            val reg = SimpleMeterRegistry()
            withRegistry(reg).registerOutboxDeadLettered(serviceTag) { 24L }

            val meter = reg.meters.single { it.id.name == "openbank.outbox.dead_lettered" }
            val convention = PrometheusNamingConvention()
            val series = convention.name(meter.id.name, meter.id.type, meter.id.baseUnit)
            val tag = meter.id.getTag("service")

            assertThat(series).isEqualTo("openbank_outbox_dead_lettered")
            assertThat(tag).isEqualTo(serviceTag)

            val selector = """$series{service="$tag"}"""
            assertThat(rulesFile).describedAs("rule file for $serviceTag").exists()
            val exprLines = rulesFile.readLines()
                .map { it.trim() }
                .filter { it.startsWith("expr:") }

            assertThat(exprLines)
                .describedAs(
                    "the dead-letter alert for '$serviceTag' must select the series the exporter " +
                        "really produces — a selector naming a label the metric does not carry " +
                        "fires never, and reads exactly like 'no problem'",
                )
                .contains("expr: $selector > 0")
        }
    }

    /**
     * The scope guard for [alertRules].
     *
     * A gate whose coverage set is maintained separately from the artifacts it covers goes green
     * about work it never did: a service that binds the gauge but is missing from the map above
     * simply is not checked, and the suite still passes. So the real set is derived — every
     * concrete `*OutboxDeadLetterGauge.kt` under a service module — and compared against the map.
     * Adding a binding without an alert now fails here rather than being discovered later by an
     * audit.
     */
    @Test
    fun `every dead-letter gauge binding is covered here`() {
        val repoRoot = File("..")
        val bindings = repoRoot.listFiles { f: File -> f.isDirectory && f.name.startsWith("openbank-") }
            .orEmpty()
            .flatMap { module ->
                File(module, "src/main/kotlin").walkTopDown()
                    // `Abstract...` is the base this module declares, not a service binding.
                    .filter { it.isFile && it.name.endsWith("OutboxDeadLetterGauge.kt") }
                    .filterNot { it.name.startsWith("Abstract") }
                    .map { module.name }
                    .toList()
            }
            .toSortedSet()

        assertThat(bindings)
            .describedAs(
                "the probe must find the known bindings — an empty set here means it is broken, not that none exist",
            )
            .isNotEmpty()

        val covered = alertRules.keys.map { "openbank-$it-service" }.toSortedSet()
        assertThat(bindings)
            .describedAs(
                "every service binding AbstractOutboxDeadLetterGauge needs a row in `alertRules` " +
                    "(and therefore an alert) — otherwise its gauge is exported and nothing reads it",
            )
            .isEqualTo(covered)
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
