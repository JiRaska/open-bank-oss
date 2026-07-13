// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class DomainMetricsTest {

    /** Wire a [DomainMetrics] to a resolvable registry, or to none (no-op mode). */
    private fun withRegistry(reg: MeterRegistry?): DomainMetrics {
        val inst = mockk<Instance<MeterRegistry>>()
        if (reg == null) {
            every { inst.isResolvable } returns false
        } else {
            every { inst.isResolvable } returns true
            every { inst.get() } returns reg
        }
        return DomainMetrics().apply { registryInstance = inst }
    }

    @Test
    fun `registerOutboxBacklog publishes a gauge that samples the supplier live`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)
        var backlog = 7

        dm.registerOutboxBacklog("ledger") { backlog }

        val gauge = reg.find("openbank.outbox.backlog").tag("service", "ledger").gauge()
        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(7.0)

        // The gauge reads the supplier on each sample, not just at registration.
        backlog = 42
        assertThat(gauge.value()).isEqualTo(42.0)
    }

    @Test
    fun `counters carry the documented low-cardinality tags`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.paymentSubmitted("sepa", "EUR")
        dm.paymentSubmitted("sepa", "EUR")

        val counter = reg.find("openbank.payments.submitted")
            .tags("type", "sepa", "currency", "EUR").counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(2.0)
    }

    @Test
    fun `every metric is a silent no-op when no MeterRegistry is resolvable`() {
        val dm = withRegistry(null)

        // None of these must throw, and nothing is registered.
        dm.registerOutboxBacklog("ledger") { 5 }
        dm.paymentSubmitted("sepa", "EUR")
        dm.outboxDead("ledger")
        dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1)).recordSuccess()
    }

    @Test
    fun `a never-succeeded workflow reads as maximally stale`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))

        val age = reg.find("openbank.workflow.last_success.age_seconds")
            .tag("workflow", "standing-order-execution").gauge()
        // Age is computed from Instant.EPOCH (1970) — trivially past any real threshold, no
        // special-casing needed for "this workflow has literally never run".
        assertThat(age).isNotNull
        assertThat(age!!.value()).isGreaterThan(Duration.ofDays(365 * 50).toSeconds().toDouble())
    }

    @Test
    fun `recordSuccess resets the age gauge close to zero`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        val recorder = dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))
        recorder.recordSuccess()

        val age = reg.find("openbank.workflow.last_success.age_seconds")
            .tag("workflow", "standing-order-execution").gauge()
        assertThat(age!!.value()).isLessThan(5.0)
    }

    @Test
    fun `expected-interval gauge reports the registered cadence in seconds`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.registerWorkflowLiveness("balance-reconciliation", Duration.ofHours(25))

        val interval = reg.find("openbank.workflow.expected_interval_seconds")
            .tag("workflow", "balance-reconciliation").gauge()
        assertThat(interval!!.value()).isEqualTo(Duration.ofHours(25).toSeconds().toDouble())
    }
}
