// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.statement.application.port.`in`.RunCloseUseCase
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseTrigger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class PeriodCloseSchedulerTest {

    @Test
    fun `successful scheduled close records liveness after the use case completes`(): Unit = runBlocking {
        val runClose = mockk<RunCloseUseCase>()
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val metrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        every { runClose.runClose(CloseTrigger.SCHEDULED) } returns Uni.createFrom().item(mockk<CloseRun>())

        val scheduler = PeriodCloseScheduler(runClose, enabled = true, domainMetrics = metrics)
        scheduler.registerLiveness()
        scheduler.monthlyClose()

        verifyOrder {
            runClose.runClose(CloseTrigger.SCHEDULED)
            liveness.recordSuccess()
        }
    }

    @Test
    fun `disabled scheduled close does not report a successful workflow run`(): Unit = runBlocking {
        val runClose = mockk<RunCloseUseCase>()
        val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
        val metrics = mockk<DomainMetrics> {
            every { registerWorkflowLiveness(any(), any()) } returns liveness
        }
        val scheduler = PeriodCloseScheduler(runClose, enabled = false, domainMetrics = metrics)
        scheduler.registerLiveness()

        scheduler.monthlyClose()

        verify(exactly = 0) { runClose.runClose(any()) }
        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
