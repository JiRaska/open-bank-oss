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
import java.math.BigDecimal
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
        dm.recordReconciliationDrift("balance_deposit_control", "CZK", BigDecimal("200.00"))
    }

    @Test
    fun `recordReconciliationDrift publishes a live gauge per control and currency`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.recordReconciliationDrift("balance_deposit_control", "CZK", BigDecimal("-219633.00"))

        val gauge = reg.find("openbank.balance.reconciliation.drift")
            .tags("control", "balance_deposit_control", "currency", "CZK").gauge()
        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(-219633.00)
    }

    @Test
    fun `recordReconciliationDrift updates the same gauge in place on the next run, not a new one`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.recordReconciliationDrift("balance_deposit_control", "CZK", BigDecimal("-219633.00"))
        dm.recordReconciliationDrift("balance_deposit_control", "CZK", BigDecimal("200.00"))

        val gauges = reg.find("openbank.balance.reconciliation.drift")
            .tags("control", "balance_deposit_control", "currency", "CZK").gauges()
        assertThat(gauges).hasSize(1)
        assertThat(gauges.first().value()).isEqualTo(200.00)
    }

    @Test
    fun `recordReconciliationDrift keeps separate gauges per currency`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.recordReconciliationDrift("balance_deposit_control", "CZK", BigDecimal("200.00"))
        dm.recordReconciliationDrift("balance_deposit_control", "EUR", BigDecimal.ZERO)

        val czk = reg.find("openbank.balance.reconciliation.drift").tag("currency", "CZK").gauge()
        val eur = reg.find("openbank.balance.reconciliation.drift").tag("currency", "EUR").gauge()
        assertThat(czk!!.value()).isEqualTo(200.00)
        assertThat(eur!!.value()).isEqualTo(0.0)
    }

    @Test
    fun `a never-succeeded workflow reads only as stale as the pod is old`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))

        val age = reg.find("openbank.workflow.last_success.age_seconds")
            .tag("workflow", "standing-order-execution").gauge()
        // Seeded at registration time (ADR-0237), NOT Instant.EPOCH: an EPOCH seed reads as
        // decades-stale from boot, so any staleness rule fires for the whole window between a
        // daily job's deploy and its first run. "Never ran" is detected by the alert layer
        // (absent() + the staleness rule after one grace period), not by an exaggerated age.
        assertThat(age).isNotNull
        assertThat(age!!.value()).isLessThan(5.0)
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
