// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import com.openbank.notification.infrastructure.persistence.repository.DeviceTokenRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.runtime.StartupEvent
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DeviceTokenSweepJobLivenessTest {

    private val repo = mockk<DeviceTokenRepository>()
    private val metrics = mockk<DomainMetrics>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val job = DeviceTokenSweepJob().also {
        it.repo = repo
        it.clock = Clock.fixed(Instant.parse("2026-08-13T03:00:00Z"), ZoneOffset.UTC)
        it.domainMetrics = metrics
    }

    @Test
    fun `registers heartbeat at startup and records success after completed sweep`(): Unit = runBlocking {
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { repo.sweepStale(any()) } returns Uni.createFrom().item(0)

        job.registerLiveness(StartupEvent())
        job.sweepStaleTokens()

        verify(exactly = 1) { metrics.registerWorkflowLiveness("device-token-stale-sweep", any()) }
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    @Test
    fun `swallowed repository failure records no success`(): Unit = runBlocking {
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { repo.sweepStale(any()) } returns Uni.createFrom().failure(IllegalStateException("db down"))

        job.registerLiveness(StartupEvent())
        job.sweepStaleTokens()

        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
