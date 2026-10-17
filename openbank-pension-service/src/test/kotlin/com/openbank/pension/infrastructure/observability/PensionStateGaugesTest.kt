// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.observability

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
 * values after, vanished label sets removed, and the refresh liveness recorded only on success.
 */
class PensionStateGaugesTest {

    private val registry = SimpleMeterRegistry()

    private fun domainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns true
        every { instance.get() } returns registry
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun snapshot(openUnmatched: Long, age: Double, activeContracts: Long = 3) = PensionStateSnapshot(
        contracts = listOf(
            StateRow(mapOf("status" to "ACTIVE", "product_line" to "DPS", "jurisdiction" to "CZ"), activeContracts),
        ),
        aggregates = listOf(StateRow(mapOf("aggregate" to "annuity_provider", "status" to "PENDING_ACTIVATION"), 1)),
        queueOldestAgeSeconds = mapOf("unmatched_payments" to age),
        queueSize = mapOf("unmatched_payments" to openUnmatched),
    )

    private fun gauge(name: String, vararg tags: String) = registry.find(name).tags(*tags).gauge()?.value()

    @Test
    fun `nothing is published before the first snapshot, measured values after it`(): Unit = runBlocking {
        val refresher = PensionStateGaugeRefresher({ snapshot(2, 7200.0) }, domainMetrics(), registry)
        refresher.register(mockk(relaxed = true))

        // A cold pod: the registry knows the gauge names but holds NO series to alert on.
        assertThat(registry.find("openbank.pension.queue.oldest_age_seconds").gauges()).isEmpty()
        assertThat(registry.find("openbank.pension.contracts").gauges()).isEmpty()

        refresher.refresh()

        assertThat(gauge("openbank.pension.queue.size", "queue", "unmatched_payments")).isEqualTo(2.0)
        assertThat(gauge("openbank.pension.queue.oldest_age_seconds", "queue", "unmatched_payments"))
            .isEqualTo(7200.0)
        assertThat(
            gauge("openbank.pension.contracts", "status", "ACTIVE", "product_line", "DPS", "jurisdiction", "CZ"),
        ).isEqualTo(3.0)
        assertThat(
            gauge("openbank.pension.aggregates", "aggregate", "annuity_provider", "status", "PENDING_ACTIVATION"),
        )
            .isEqualTo(1.0)
        assertThat(
            registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED).tag("workflow", PensionStateGaugeRefresher.WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `a label set that disappears is removed, not frozen at its last value`() {
        val publisher = PensionStateGaugePublisher(registry)
        publisher.publish(snapshot(2, 60.0))
        publisher.publish(snapshot(0, 0.0).copy(contracts = emptyList()))

        assertThat(registry.find("openbank.pension.contracts").gauges()).isEmpty()
        assertThat(gauge("openbank.pension.queue.size", "queue", "unmatched_payments")).isEqualTo(0.0)
    }

    @Test
    fun `a failing snapshot keeps the last values and records no liveness success`(): Unit = runBlocking {
        var fail = false
        val refresher = PensionStateGaugeRefresher(
            { if (fail) error("database unavailable") else snapshot(4, 30.0) },
            domainMetrics(),
            registry,
        )
        refresher.register(mockk(relaxed = true))
        fail = true
        refresher.refresh()

        assertThat(
            registry.find(WorkflowLivenessMetrics.SUCCESS_RECORDED).tag("workflow", PensionStateGaugeRefresher.WORKFLOW)
                .gauge()?.value(),
        ).isEqualTo(0.0)
        assertThat(registry.find("openbank.pension.queue.size").gauges()).isEmpty()
    }
}
