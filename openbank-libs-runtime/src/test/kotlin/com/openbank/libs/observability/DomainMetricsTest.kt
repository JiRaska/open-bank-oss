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
        dm.registerWorkflowRun("standing-order-execution", Duration.ofMinutes(5)).record(Duration.ofSeconds(3))
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
    fun `a freshly registered workflow reads an age of seconds, not decades`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))

        val age = reg.find("openbank.workflow.last_success.age_seconds")
            .tag("workflow", "standing-order-execution").gauge()
        assertThat(age).isNotNull
        // Pinned BY VALUE on purpose. The gauge used to seed from Instant.EPOCH and read ~1.8e9
        // seconds on every fresh pod, which fires WorkflowLivenessStale (age > 2x interval, for:
        // 15m) 15 minutes after every deploy for every daily workflow. assertThat(age).isNotNull()
        // passes against exactly that value, so the assertion has to be an upper bound: a workflow
        // registered moments ago is seconds old, and anything approaching the 2-day threshold of a
        // daily job is the regression.
        assertThat(age!!.value())
            .describedAs("the age gauge must be seeded at registration, not at Instant.EPOCH")
            .isLessThan(Duration.ofHours(1).toSeconds().toDouble())
    }

    @Test
    fun `a freshly registered workflow reports that it has not succeeded yet`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))

        // The one bit the boot-seed gives up, published rather than lost: with the age gauge now
        // small in both cases, this flag is the only thing separating "never succeeded since boot"
        // from "succeeded a moment ago", and it is what the sentinel triages on.
        val recorded = reg.find("openbank.workflow.success.recorded")
            .tag("workflow", "standing-order-execution").gauge()
        assertThat(recorded!!.value()).isEqualTo(0.0)
    }

    @Test
    fun `recordSuccess resets the age gauge close to zero and flips the success flag`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        val recorder = dm.registerWorkflowLiveness("standing-order-execution", Duration.ofDays(1))
        recorder.recordSuccess()

        val age = reg.find("openbank.workflow.last_success.age_seconds")
            .tag("workflow", "standing-order-execution").gauge()
        assertThat(age!!.value()).isLessThan(5.0)
        val recorded = reg.find("openbank.workflow.success.recorded")
            .tag("workflow", "standing-order-execution").gauge()
        assertThat(recorded!!.value()).isEqualTo(1.0)
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

    // ── Workflow run duration (#6169) ─────────────────────────────────────────

    @Test
    fun `a cold pod reports zero runs and its declared budget, and never a duration`() {
        val reg = SimpleMeterRegistry()
        val dm = withRegistry(reg)

        // Registration only — no run has happened yet. This is the t=0 state the alert rule is
        // designed against, and re-deriving it is the step WorkflowLivenessStale skipped: it
        // shipped asserting a seed it did not have and fired 15 minutes after every deploy (#2239,
        // fixed by #4208). A timer with no observations is a FOURTH state beside healthy /
        // degraded / absent, and it must read as neither "instant" nor "breached".
        dm.registerWorkflowRun("agent-oversight-sweep", Duration.ofMinutes(5))

        val budget = reg.find(WorkflowRunMetrics.RUN_BUDGET_SECONDS)
            .tag(WorkflowRunMetrics.WORKFLOW_TAG, "agent-oversight-sweep").gauge()
        assertThat(budget).isNotNull
        assertThat(budget!!.value()).isEqualTo(Duration.ofMinutes(5).toSeconds().toDouble())

        val timers = reg.find(WorkflowRunMetrics.RUN_DURATION)
            .tag(WorkflowRunMetrics.WORKFLOW_TAG, "agent-oversight-sweep").timers()
        // Both outcomes exist from pod start, so an ABSENT series means "not instrumented" rather
        // than "has never run" — the distinction registerFeedFetch documents for its counters.
        assertThat(timers.map { it.id.getTag(WorkflowRunMetrics.OUTCOME_TAG) })
            .containsExactlyInAnyOrder(WorkflowRunMetrics.OUTCOME_SUCCESS, WorkflowRunMetrics.OUTCOME_FAILURE)
        assertThat(timers.sumOf { it.count() })
            .describedAs("count = 0 is what makes the alert's denominator vanish and the rule silent")
            .isEqualTo(0L)
        assertThat(timers.sumOf { it.totalTime(java.util.concurrent.TimeUnit.SECONDS) }).isEqualTo(0.0)
    }

    @Test
    fun `a failing run is still timed, under its own outcome tag`() {
        val reg = SimpleMeterRegistry()
        val recorder = withRegistry(reg).registerWorkflowRun("agent-oversight-sweep", Duration.ofMinutes(5))

        recorder.record(Duration.ofSeconds(9), succeeded = true)
        recorder.record(Duration.ofSeconds(1), succeeded = false)

        fun timer(outcome: String) = reg.find(WorkflowRunMetrics.RUN_DURATION)
            .tags(WorkflowRunMetrics.WORKFLOW_TAG, "agent-oversight-sweep", WorkflowRunMetrics.OUTCOME_TAG, outcome)
            .timer()!!
        assertThat(timer(WorkflowRunMetrics.OUTCOME_SUCCESS).count()).isEqualTo(1L)
        assertThat(timer(WorkflowRunMetrics.OUTCOME_SUCCESS).totalTime(java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(9.0)
        // A fail-fast run must not silently pull the mean down without being separable: the alert
        // sums over outcome (wall-clock spent failing is still wall-clock spent), triage splits.
        assertThat(timer(WorkflowRunMetrics.OUTCOME_FAILURE).count()).isEqualTo(1L)
        assertThat(timer(WorkflowRunMetrics.OUTCOME_FAILURE).totalTime(java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(1.0)
    }
}
