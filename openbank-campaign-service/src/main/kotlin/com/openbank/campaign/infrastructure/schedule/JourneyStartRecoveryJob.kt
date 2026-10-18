// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.schedule

import com.openbank.campaign.application.usecase.JourneyStartRecovery
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

@Startup
@ApplicationScoped
class JourneyStartRecoveryJob(private val recovery: JourneyStartRecovery, private val metrics: DomainMetrics) {
    private companion object {
        const val TICK_SECONDS = 5L
    }

    private lateinit var liveness: WorkflowLivenessRecorder

    @PostConstruct
    fun register() {
        liveness = metrics.registerWorkflowLiveness(
            "campaign-journey-start-recovery",
            Duration.ofSeconds(TICK_SECONDS),
        )
    }

    @Scheduled(
        every = "5s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "campaign-journey-start-recovery",
    )
    suspend fun tick() {
        recovery.tick()
        liveness.recordSuccess()
    }
}
