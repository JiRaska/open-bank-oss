// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.onboarding

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.pension.application.onboarding.DispatchResult
import com.openbank.pension.application.onboarding.OnboardingTimers
import com.openbank.pension.application.onboarding.TransferInTimers
import com.openbank.pension.application.onboarding.workflow.OnboardingActivities
import com.openbank.pension.application.onboarding.workflow.OnboardingOutcome
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflow
import com.openbank.pension.application.onboarding.workflow.OnboardingWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferInActivities
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflow
import com.openbank.pension.application.onboarding.workflow.TransferInWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferOutActivities
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflow
import com.openbank.pension.application.onboarding.workflow.TransferOutWorkflowImpl
import com.openbank.pension.application.onboarding.workflow.TransferOutcome
import com.openbank.pension.domain.onboarding.ActivationTrigger
import com.openbank.pension.domain.transfer.FundsArrival
import com.openbank.pension.domain.transfer.TransferStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * The three workflows executed in Temporal's time-skipping test environment, with mocked
 * activities. What is asserted is what the workflow DOES — which activity runs, and when relative
 * to the cooling-off and deadline timers — not that a predicate returns true.
 */
class PensionWorkflowsTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private val onboarding = mockk<OnboardingActivities>(relaxed = true)
    private val transferIn = mockk<TransferInActivities>(relaxed = true)
    private val transferOut = mockk<TransferOutActivities>(relaxed = true)
    private val id: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // The same Kotlin-aware converter the production client uses (TemporalClientProducer).
        val converter = DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(
            JacksonJsonPayloadConverter(
                JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule(),
            ),
        )
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
                .build(),
        )
        val worker = env.newWorker(QUEUE)
        worker.registerWorkflowImplementationTypes(
            OnboardingWorkflowImpl::class.java,
            TransferInWorkflowImpl::class.java,
            TransferOutWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(onboarding, transferIn, transferOut)
        env.start()
        client = env.workflowClient
        every { onboarding.activate(id) } returns true
        every { transferIn.dispatch(id) } returns DispatchResult.SENT
        every { transferOut.valuate(id) } returns true
        every { transferOut.redeem(id) } returns "r-1"
    }

    @AfterEach
    fun tearDown() = env.close()

    private fun options() = WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("wf-$id").build()

    private val timers = OnboardingTimers(14, ActivationTrigger.FIRST_CONTRIBUTION, 90)

    @Test
    fun `a contribution inside the cooling-off period activates only once it has ended`() {
        val wf = client.newWorkflowStub(OnboardingWorkflow::class.java, options())
        WorkflowClient.start(wf::run, id, timers)
        wf.contributionReceived()
        env.sleep(Duration.ofDays(13))
        verify(exactly = 0) { onboarding.activate(any()) }
        env.sleep(Duration.ofDays(2))
        assertThat(
            WorkflowStub.fromTyped(wf).getResult(OnboardingOutcome::class.java),
        ).isEqualTo(OnboardingOutcome.ACTIVATED)
        verify(exactly = 1) { onboarding.activate(id) }
    }

    @Test
    fun `withdrawal in the cooling-off period ends the workflow without activation`() {
        val wf = client.newWorkflowStub(OnboardingWorkflow::class.java, options())
        WorkflowClient.start(wf::run, id, timers)
        env.sleep(Duration.ofDays(3))
        wf.withdrawn()
        assertThat(
            WorkflowStub.fromTyped(wf).getResult(OnboardingOutcome::class.java),
        ).isEqualTo(OnboardingOutcome.WITHDRAWN)
        verify(exactly = 0) { onboarding.activate(any()) }
    }

    @Test
    fun `a contract never funded lapses at the activation deadline`() {
        val wf = client.newWorkflowStub(OnboardingWorkflow::class.java, options())
        assertThat(wf.run(id, timers)).isEqualTo(OnboardingOutcome.EXPIRED)
        verify { onboarding.expire(id, any()) }
        verify(exactly = 0) { onboarding.activate(any()) }
    }

    @Test
    fun `activation on signature still waits for the cooling-off period`() {
        val wf = client.newWorkflowStub(OnboardingWorkflow::class.java, options())
        assertThat(
            wf.run(id, OnboardingTimers(14, ActivationTrigger.SIGNATURE, 90)),
        ).isEqualTo(OnboardingOutcome.ACTIVATED)
        verify(exactly = 1) { onboarding.activate(id) }
    }

    private val inTimers = TransferInTimers(responseDeadlineDays = 30, fundsGraceDays = 30, coolingOffDays = 14)
    private val arrival = FundsArrival(BigDecimal("5000"), "CZK", LocalDate.parse("2015-01-01"), emptyList())

    @Test
    fun `transfer-in completes when funds arrive, after the cooling-off period`() {
        val wf = client.newWorkflowStub(TransferInWorkflow::class.java, options())
        WorkflowClient.start(wf::run, id, inTimers)
        wf.accepted()
        wf.fundsReceived(arrival)
        env.sleep(Duration.ofDays(5))
        verify(exactly = 0) { transferIn.complete(any(), any()) }
        assertThat(
            WorkflowStub.fromTyped(wf).getResult(TransferOutcome::class.java),
        ).isEqualTo(TransferOutcome.COMPLETED)
        verify { transferIn.recordAccepted(id) }
        verify { transferIn.complete(id, arrival) }
    }

    @Test
    fun `a rejection by the ceding provider compensates`() {
        val wf = client.newWorkflowStub(TransferInWorkflow::class.java, options())
        WorkflowClient.start(wf::run, id, inTimers)
        wf.rejected("contract number unknown")
        assertThat(
            WorkflowStub.fromTyped(wf).getResult(TransferOutcome::class.java),
        ).isEqualTo(TransferOutcome.REJECTED)
        verify { transferIn.fail(id, TransferStatus.REJECTED, "contract number unknown") }
        verify(exactly = 0) { transferIn.complete(any(), any()) }
    }

    @Test
    fun `silence past the deadline times out and compensates`() {
        val wf = client.newWorkflowStub(TransferInWorkflow::class.java, options())
        assertThat(wf.run(id, inTimers)).isEqualTo(TransferOutcome.TIMED_OUT)
        verify { transferIn.fail(id, TransferStatus.TIMED_OUT, any()) }
    }

    @Test
    fun `an unreachable ceding provider fails the transfer with compensation`() {
        every { transferIn.dispatch(id) } throws IllegalStateException("down")
        val wf = client.newWorkflowStub(TransferInWorkflow::class.java, options())
        assertThat(wf.run(id, inTimers)).isEqualTo(TransferOutcome.FAILED)
        verify { transferIn.fail(id, TransferStatus.FAILED, any()) }
    }

    @Test
    fun `transfer-out values, redeems and settles`() {
        val wf = client.newWorkflowStub(TransferOutWorkflow::class.java, options())
        assertThat(wf.run(id)).isEqualTo(TransferOutcome.COMPLETED)
        verify { transferOut.redeem(id) }
        verify { transferOut.settle(id) }
        verify(exactly = 0) { transferOut.compensate(any(), any()) }
    }

    @Test
    fun `a failed settlement reverses the redemption`() {
        every { transferOut.settle(id) } throws IllegalStateException("counterparty refused")
        val wf = client.newWorkflowStub(TransferOutWorkflow::class.java, options())
        assertThat(wf.run(id)).isEqualTo(TransferOutcome.FAILED)
        verify { transferOut.compensate(id, any()) }
    }

    @Test
    fun `a rejected valuation stops before any redemption`() {
        every { transferOut.valuate(id) } returns false
        val wf = client.newWorkflowStub(TransferOutWorkflow::class.java, options())
        assertThat(wf.run(id)).isEqualTo(TransferOutcome.REJECTED)
        verify(exactly = 0) { transferOut.redeem(any()) }
    }

    private companion object {
        const val QUEUE = "test-pension"
    }
}
