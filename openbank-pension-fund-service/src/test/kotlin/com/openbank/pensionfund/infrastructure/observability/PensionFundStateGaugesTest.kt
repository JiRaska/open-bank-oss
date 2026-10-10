// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The state gauges are COLD-POD SAFE BY ABSENCE: no series before the first snapshot, measured
 * values after, a figure not yet established (no published NAV) absent rather than zero, vanished
 * label sets removed, and the refresh liveness recorded only on success.
 */
class PensionFundStateGaugesTest {

    private val registry = SimpleMeterRegistry()

    private fun domainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private val published = FundState(
        isin = "CZ0000000201",
        currency = "CZK",
        unitsOutstanding = 1500.5,
        netAssets = 1650.55,
        navPerUnit = 1.1,
        lastPublishedAgeSeconds = 36_000.0,
        pendingOrdersOldestAgeSeconds = 7200.0,
    )
    private val launched = FundState("CZ0000000202", "CZK", 0.0, null, null, null, 0.0)

    private fun snapshot(funds: List<FundState> = listOf(published, launched)) = PensionFundStateSnapshot(
        funds = funds,
        pendingOrders = listOf(StateRow(mapOf("fund" to "CZ0000000201", "type" to "SUBSCRIBE"), 3)),
        aggregates = listOf(StateRow(mapOf("aggregate" to "nav", "status" to "CALCULATED"), 1)),
        queueOldestAgeSeconds = mapOf("navs_awaiting_approval" to 600.0, "orders_pending" to 7200.0),
        queueSize = mapOf("navs_awaiting_approval" to 1L, "orders_pending" to 3L),
    )

    private fun gauge(name: String, vararg tags: String) = registry.find(name).tags(*tags).gauge()?.value()

    private fun recorded() = registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED)
        .tag("workflow", PensionFundStateGaugeRefresher.WORKFLOW).gauge()?.value()

    @Test
    fun `nothing is published before the first snapshot, measured values after it`(): Unit = runBlocking {
        val refresher = PensionFundStateGaugeRefresher({ snapshot() }, domainMetrics(), registry)
        refresher.register(mockk(relaxed = true))

        // A cold pod: the registry knows the gauge names but holds NO series to alert on.
        listOf(
            "openbank.pension_fund.nav.last_published_age_seconds",
            "openbank.pension_fund.orders.pending_oldest_age_seconds",
            "openbank.pension_fund.queue.oldest_age_seconds",
            "openbank.pension_fund.net_assets",
            "openbank.pension_fund.units_outstanding",
        ).forEach { assertThat(registry.find(it).gauges()).describedAs(it).isEmpty() }
        assertThat(recorded()).isEqualTo(0.0)

        refresher.refresh()

        assertThat(gauge("openbank.pension_fund.units_outstanding", "fund", "CZ0000000201")).isEqualTo(1500.5)
        assertThat(gauge("openbank.pension_fund.net_assets", "fund", "CZ0000000201", "currency", "CZK"))
            .isEqualTo(1650.55)
        assertThat(gauge("openbank.pension_fund.nav.per_unit", "fund", "CZ0000000201", "currency", "CZK"))
            .isEqualTo(1.1)
        assertThat(gauge("openbank.pension_fund.nav.last_published_age_seconds", "fund", "CZ0000000201"))
            .isEqualTo(36_000.0)
        assertThat(gauge("openbank.pension_fund.orders.pending_oldest_age_seconds", "fund", "CZ0000000201"))
            .isEqualTo(7200.0)
        assertThat(gauge("openbank.pension_fund.orders.pending", "fund", "CZ0000000201", "type", "SUBSCRIBE"))
            .isEqualTo(3.0)
        assertThat(gauge("openbank.pension_fund.aggregates", "aggregate", "nav", "status", "CALCULATED"))
            .isEqualTo(1.0)
        assertThat(gauge("openbank.pension_fund.queue.size", "queue", "navs_awaiting_approval")).isEqualTo(1.0)
        assertThat(gauge("openbank.pension_fund.queue.oldest_age_seconds", "queue", "orders_pending"))
            .isEqualTo(7200.0)
        // A fund that has never published a NAV: its units (0) are measured, its NAV figures are not.
        assertThat(gauge("openbank.pension_fund.units_outstanding", "fund", "CZ0000000202")).isEqualTo(0.0)
        assertThat(gauge("openbank.pension_fund.orders.pending_oldest_age_seconds", "fund", "CZ0000000202"))
            .isEqualTo(0.0)
        listOf(
            "openbank.pension_fund.net_assets",
            "openbank.pension_fund.nav.per_unit",
            "openbank.pension_fund.nav.last_published_age_seconds",
        ).forEach { assertThat(registry.find(it).tag("fund", "CZ0000000202").gauges()).describedAs(it).isEmpty() }
        assertThat(recorded()).isEqualTo(1.0)
    }

    @Test
    fun `a fund that disappears (closed) is removed, not frozen at its last value`() {
        val publisher = PensionFundStateGaugePublisher(registry)
        publisher.publish(snapshot())
        publisher.publish(snapshot(funds = listOf(launched)))

        assertThat(registry.find("openbank.pension_fund.units_outstanding").tag("fund", "CZ0000000201").gauges())
            .isEmpty()
        assertThat(registry.find("openbank.pension_fund.nav.last_published_age_seconds").gauges()).isEmpty()
        assertThat(gauge("openbank.pension_fund.units_outstanding", "fund", "CZ0000000202")).isEqualTo(0.0)
    }

    @Test
    fun `a failing snapshot publishes nothing and records no liveness success`(): Unit = runBlocking {
        val refresher = PensionFundStateGaugeRefresher({ error("database unavailable") }, domainMetrics(), registry)
        refresher.register(mockk(relaxed = true))
        refresher.refresh()

        assertThat(recorded()).isEqualTo(0.0)
        assertThat(registry.find("openbank.pension_fund.queue.size").gauges()).isEmpty()
    }
}
