// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
    }
}
