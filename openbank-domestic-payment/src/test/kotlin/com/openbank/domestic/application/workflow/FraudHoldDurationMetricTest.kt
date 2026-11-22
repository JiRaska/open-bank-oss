// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.observability.FraudHoldDurationMeterFilter
import com.openbank.libs.fincrime.ScreeningDecision
import com.uber.m3.tally.RootScopeBuilder
import com.uber.m3.tally.Scope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.reporter.MicrometerClientStatsReporter
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `openbank_domestic_fraud_hold_duration{outcome}` measures workflow time from the HOLD to the
 * decision, through the same Micrometer reporter the deployed client uses.
 */
class FraudHoldDurationMetricTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var scope: Scope
    private lateinit var activities: DomesticPaymentActivities

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        registry.config().meterFilter(FraudHoldDurationMeterFilter.filter())
        scope = RootScopeBuilder()
            .reporter(MicrometerClientStatsReporter(registry))
            .reportEvery(com.uber.m3.util.Duration.ofMillis(10.0))
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowServiceStubsOptions(WorkflowServiceStubsOptions.newBuilder().setMetricsScope(scope).build())
                .setMetricsScope(scope)
                .build(),
        )
        val worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
        scope.close()
    }

    private fun held(): UUID = UUID.randomUUID().also {
        every { activities.screenPayment(it) } returns ScreeningDecision.CLEAR
        every { activities.fraudGate(it) } returns FraudGateAction.HOLD
        every { activities.submitScheme(it) } returns DomesticPaymentStatus.VALIDATED
    }

    private fun run(paymentId: UUID, waitFor: Duration, decision: FraudReviewDecision?): DomesticPaymentStatus {
        val stub = env.workflowClient.newWorkflowStub(
            DomesticPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
        )
        val untyped = WorkflowStub.fromTyped(stub)
        untyped.start(paymentId)
        env.sleep(waitFor)
        if (decision != null) stub.resolveFraudHold(decision, fraudReviewCaseKey(paymentId))
        return untyped.getResult(DomesticPaymentStatus::class.java)
    }

    private fun timer(outcome: String) = registry.find(DomesticPaymentWorkflowImpl.FRAUD_HOLD_DURATION_METRIC)
        .tag("outcome", outcome)
        .timer()

    private fun awaitTimer(outcome: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (timer(outcome) == null && System.nanoTime() < deadline) Thread.sleep(20)
    }

    @Test
    fun `a released hold records its wait under outcome RELEASED`() {
        assertThat(run(held(), Duration.ofHours(3), FraudReviewDecision.RELEASE))
            .isEqualTo(DomesticPaymentStatus.VALIDATED)
        awaitTimer("RELEASED")

        val t = timer("RELEASED")!!
        assertThat(t.count()).isEqualTo(1)
        assertThat(t.totalTime(TimeUnit.MINUTES)).isBetween(179.0, 190.0)
        // The bucket boundaries are what make p50/p95 answerable: 3 h is above 2 h and below 4 h.
        val buckets = t.takeSnapshot().histogramCounts().associate { it.bucket(TimeUnit.HOURS) to it.count() }
        assertThat(buckets[2.0]).isEqualTo(0.0)
        assertThat(buckets[4.0]).isEqualTo(1.0)
        assertThat(timer("REJECTED")).isNull()
    }

    @Test
    fun `an analyst rejection and a timeout are recorded under their own outcomes`() {
        run(held(), Duration.ofMinutes(10), FraudReviewDecision.REJECT)
        run(held(), Duration.ofSeconds(1), null)
        awaitTimer("REJECTED")
        awaitTimer("TIMED_OUT")

        assertThat(timer("REJECTED")!!.count()).isEqualTo(1)
        assertThat(timer("TIMED_OUT")!!.totalTime(TimeUnit.HOURS)).isBetween(47.9, 48.5)
        assertThat(timer("RELEASED")).isNull()
    }

    companion object {
        private const val TASK_QUEUE = "test-fraud-hold-duration"
    }
}
