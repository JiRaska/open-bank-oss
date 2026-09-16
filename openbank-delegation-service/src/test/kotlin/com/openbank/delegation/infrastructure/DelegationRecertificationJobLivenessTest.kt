// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.application.port.out.DelegationRecertificationRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DelegationRecertificationJobLivenessTest {

    private val grants = mockk<DelegationRepository>()
    private val cycles = mockk<DelegationRecertificationRepository>()
    private val metrics = mockk<DomainMetrics>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val job = DelegationRecertificationJob(
        grants,
        cycles,
        Clock.fixed(Instant.parse("2026-09-08T08:00:00Z"), ZoneOffset.UTC),
        metrics,
    )

    @Test
    fun `records a heartbeat after a completed no-op sweep`(): Unit = runBlocking {
        io.mockk.every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        coEvery { grants.findActiveWithRecertificationAudience() } returns emptyList()

        job.registerLiveness(StartupEvent())
        job.materializeDueReviews()

        verify(exactly = 1) { metrics.registerWorkflowLiveness("delegation-recertification-sweep", any()) }
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    @Test
    fun `does not report a heartbeat after a failed sweep`(): Unit = runBlocking {
        io.mockk.every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        coEvery { grants.findActiveWithRecertificationAudience() } throws IllegalStateException("db down")

        job.registerLiveness(StartupEvent())
        job.materializeDueReviews()

        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
