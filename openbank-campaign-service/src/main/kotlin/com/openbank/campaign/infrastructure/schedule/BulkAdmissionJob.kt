// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.schedule

import com.openbank.campaign.application.usecase.BulkAdmissionService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/** Cluster-wide rate and cross-pod exclusion live in campaign_admission_budget, not this annotation. */
@Startup
@ApplicationScoped
class BulkAdmissionJob(private val admission: BulkAdmissionService, private val metrics: DomainMetrics) {
    private lateinit var liveness: WorkflowLivenessRecorder

    @PostConstruct
    fun register() {
        liveness = metrics.registerWorkflowLiveness("campaign-bulk-admission", Duration.ofSeconds(TICK_SECONDS))
    }

    @Scheduled(
        every = "5s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "campaign-bulk-admission",
    )
    suspend fun tick() {
        admission.tick()
        liveness.recordSuccess()
    }

    private companion object {
        const val TICK_SECONDS = 5L
    }
}
