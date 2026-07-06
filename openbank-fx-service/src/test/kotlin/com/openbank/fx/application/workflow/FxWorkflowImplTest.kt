// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.screening.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class FxWorkflowImplTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: FxActivities

    companion object {
        private const val TASK_QUEUE = "test-fx"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(FxWorkflowImpl::class.java)
        activities = mockk<FxActivities>(relaxed = true)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun workflowStub(): FxWorkflow = env.workflowClient.newWorkflowStub(
        FxWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
    )

    @Test
    fun `CLEAR decision settles the conversion and returns SETTLED`() {
        val conversionId = UUID.randomUUID()
        every { activities.screenConversion(conversionId) } returns ScreeningDecision.CLEAR

        val result = workflowStub().process(conversionId)

        assertThat(result).isEqualTo(FxConversionStatus.SETTLED)
        verify { activities.settleConversion(conversionId) }
        verify { activities.shadowFraudScore(conversionId) }
        verify(exactly = 0) { activities.blockConversion(any()) }
        verify(exactly = 0) { activities.holdConversion(any()) }
    }

    @Test
    fun `BLOCK decision fails the conversion and returns FAILED`() {
        val conversionId = UUID.randomUUID()
        every { activities.screenConversion(conversionId) } returns ScreeningDecision.BLOCK

        val result = workflowStub().process(conversionId)

        assertThat(result).isEqualTo(FxConversionStatus.FAILED)
        verify { activities.blockConversion(conversionId) }
        verify(exactly = 0) { activities.settleConversion(any()) }
        verify(exactly = 0) { activities.holdConversion(any()) }
    }

    @Test
    fun `REVIEW decision holds the conversion and returns PENDING`() {
        val conversionId = UUID.randomUUID()
        every { activities.screenConversion(conversionId) } returns ScreeningDecision.REVIEW

        val result = workflowStub().process(conversionId)

        assertThat(result).isEqualTo(FxConversionStatus.PENDING)
        verify { activities.holdConversion(conversionId) }
        verify(exactly = 0) { activities.settleConversion(any()) }
        verify(exactly = 0) { activities.blockConversion(any()) }
    }

    @Test
    fun `fraud shadow score is always run regardless of screening decision`() {
        val conversionId = UUID.randomUUID()
        every { activities.screenConversion(conversionId) } returns ScreeningDecision.BLOCK

        workflowStub().process(conversionId)

        verify(exactly = 1) { activities.shadowFraudScore(conversionId) }
    }
}
