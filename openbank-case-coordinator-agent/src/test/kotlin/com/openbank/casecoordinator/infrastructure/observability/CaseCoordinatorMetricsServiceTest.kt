// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class CaseCoordinatorMetricsServiceTest {
    private lateinit var registry: MeterRegistry
    private lateinit var metrics: CaseCoordinatorMetricsService

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = CaseCoordinatorMetricsService().apply { registry = this@CaseCoordinatorMetricsServiceTest.registry }
        metrics.init()
    }

    @Test
    fun `records kill-switch event counter`() {
        metrics.recordKillSwitchEvent("rca-investigator", "set")

        val counter = registry.counter(
            "openbank.casecoordinator.kill_switch.events",
            "scope",
            "rca-investigator",
            "eventType",
            "set",
        )
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `sets kill-switch active gauge for scope`() {
        metrics.setKillSwitchActive("rca-investigator", true)

        val gauge = registry.find("openbank.casecoordinator.kill_switch.active")
            .tag("scope", "rca-investigator")
            .gauge()
        assertThat(gauge?.value()).isEqualTo(1.0)
    }

    @Test
    fun `active gauge can be cleared`() {
        metrics.setKillSwitchActive("rca-investigator", true)
        metrics.setKillSwitchActive("rca-investigator", false)

        val gauge = registry.find("openbank.casecoordinator.kill_switch.active")
            .tag("scope", "rca-investigator")
            .gauge()
        assertThat(gauge?.value()).isEqualTo(0.0)
    }

    @Test
    fun `records case opened counter`() {
        metrics.recordCaseOpened("INCIDENT_RESPONSE", "SHADOW")

        val counter = registry.counter(
            "openbank.casecoordinator.cases_opened",
            "caseClass",
            "incident_response",
            "deliveryMode",
            "shadow",
        )
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `records halt latency timer`() {
        metrics.recordHaltLatency("INCIDENT_RESPONSE", "SHADOW", 218_000)

        val timer = registry.timer(
            "openbank.casecoordinator.halt_latency_seconds",
            "caseClass",
            "incident_response",
            "deliveryMode",
            "shadow",
        )
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.max(TimeUnit.MILLISECONDS)).isEqualTo(218_000.0)
    }

    @Test
    fun `publishes active cases via multi gauge`() {
        metrics.updateActiveCases(
            mapOf(
                ("INCIDENT_RESPONSE" to "OPEN") to 2,
                ("INCIDENT_RESPONSE" to "HALTED") to 1,
            ),
        )

        val gauges = registry.meters.filter { it.id.name == "openbank.casecoordinator.active_cases" }
        assertThat(gauges).hasSize(2)
    }
}
