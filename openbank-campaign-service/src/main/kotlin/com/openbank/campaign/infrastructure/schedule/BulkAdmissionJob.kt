// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.schedule

import com.openbank.campaign.application.usecase.BulkAdmissionService
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped

/** Cluster-wide rate and cross-pod exclusion live in campaign_admission_budget, not this annotation. */
@ApplicationScoped
class BulkAdmissionJob(private val admission: BulkAdmissionService) {
    @Scheduled(
        every = "5s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        identity = "campaign-bulk-admission",
    )
    suspend fun tick() = admission.tick()
}
