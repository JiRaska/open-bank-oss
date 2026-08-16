// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
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

class DelegationExpirationJobLivenessTest {

    private val repository = mockk<DelegationRepository>()
    private val metrics = mockk<DomainMetrics>()
    private val liveness = mockk<WorkflowLivenessRecorder>(relaxed = true)
    private val job = DelegationExpirationJob().also {
        it.delegationRepo = repository
        it.clock = Clock.fixed(Instant.parse("2026-08-16T05:00:00Z"), ZoneOffset.UTC)
        it.domainMetrics = metrics
    }

    @Test
    fun `registers heartbeat and records success after completed sweep`(): Unit = runBlocking {
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { repository.findExpiredActive(any()) } returns Uni.createFrom().item(emptyList())

        job.registerLiveness(StartupEvent())
        job.sweepExpiredGrants()

        verify(exactly = 1) { metrics.registerWorkflowLiveness("delegation-expiration-sweep", any()) }
        verify(exactly = 1) { liveness.recordSuccess() }
    }

    @Test
    fun `caught repository failure records no liveness success`(): Unit = runBlocking {
        every { metrics.registerWorkflowLiveness(any(), any()) } returns liveness
        every { repository.findExpiredActive(any()) } returns
            Uni.createFrom().failure(IllegalStateException("db down"))

        job.registerLiveness(StartupEvent())
        job.sweepExpiredGrants()

        verify(exactly = 0) { liveness.recordSuccess() }
    }
}
