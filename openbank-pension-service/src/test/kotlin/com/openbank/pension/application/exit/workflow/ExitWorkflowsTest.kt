// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/** Temporal test environment: real workflow code, durable timers on skipped time, mocked activities. */
class ExitWorkflowsTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var activities: ExitActivities
    private val queue = "test-pension-exit"

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        activities = mockk(relaxed = true)
        val worker = env.newWorker(queue)
        worker.registerWorkflowImplementationTypes(
            EarlyTerminationWorkflowImpl::class.java,
            RegularPayoutWorkflowImpl::class.java,
            DeathSettlementWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() = env.close()

    private fun <T> stub(type: Class<T>, id: String): T = env.workflowClient.newWorkflowStub(
        type,
        WorkflowOptions.newBuilder().setTaskQueue(queue).setWorkflowId(id).build(),
    )

    @Test
    fun `early termination pays nothing before the notice period ends, then redeems, settles and closes`() {
        every { activities.terminationRedeem("n1") } returns true
        val wf = stub(EarlyTerminationWorkflow::class.java, "t-n1")
        WorkflowClient.start({ wf.run("n1", 30) })

        env.sleep(Duration.ofDays(29))
        verify(exactly = 0) { activities.terminationRedeem(any()) }

        env.sleep(Duration.ofDays(2))
        verifyOrder {
            activities.terminationRedeem("n1")
            activities.terminationSettle("n1")
            activities.terminationComplete("n1")
        }
    }

    @Test
    fun `a notice superseded by a death claim stops after the redeem check`() {
        every { activities.terminationRedeem("n2") } returns false
        stub(EarlyTerminationWorkflow::class.java, "t-n2").run("n2", 0)
        verify(exactly = 0) { activities.terminationSettle(any()) }
        verify(exactly = 0) { activities.terminationComplete(any()) }
    }

    @Test
    fun `a single payout pays once and completes`() {
        every { activities.payoutPlan("p1") } returns emptyList()
        stub(RegularPayoutWorkflow::class.java, "p-p1").run("p1")
        verifyOrder {
            activities.payoutSingle("p1")
            activities.payoutComplete("p1")
        }
    }

    @Test
    fun `a phased withdrawal pays each installment only when it falls due`() {
        val start = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(env.currentTimeMillis()), ZoneOffset.UTC)
        val plan = (1..3).map { PlannedInstallment(it, start.plusMonths(it.toLong()).toEpochDay()).encode() }
        every { activities.payoutPlan("p2") } returns plan
        every { activities.payoutInstallment("p2", any()) } returns true
        val wf = stub(RegularPayoutWorkflow::class.java, "p-p2")
        WorkflowClient.start({ wf.run("p2") })

        env.sleep(Duration.ofDays(32))
        verify(exactly = 1) { activities.payoutInstallment("p2", 1) }
        verify(exactly = 0) { activities.payoutInstallment("p2", 2) }

        env.sleep(Duration.ofDays(70))
        verify(exactly = 1) { activities.payoutInstallment("p2", 3) }
        verify(exactly = 1) { activities.payoutComplete("p2") }
    }

    @Test
    fun `a schedule interrupted by a death claim stops without completing`() {
        every { activities.payoutPlan("p3") } returns
            listOf(PlannedInstallment(1, 0).encode(), PlannedInstallment(2, 0).encode())
        every { activities.payoutInstallment("p3", 1) } returns false
        stub(RegularPayoutWorkflow::class.java, "p-p3").run("p3")
        verify(exactly = 0) { activities.payoutInstallment("p3", 2) }
        verify(exactly = 0) { activities.payoutComplete("p3") }
    }

    @Test
    fun `death settlement redeems, pays every claimant and settles`() {
        every { activities.deathClaimants("c1") } returns listOf("a", "b")
        stub(DeathSettlementWorkflow::class.java, "d-c1").run("c1")
        verifyOrder {
            activities.deathRedeem("c1")
            activities.deathPayClaimant("c1", "a")
            activities.deathPayClaimant("c1", "b")
            activities.deathSettle("c1")
        }
    }
}
