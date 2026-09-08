// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.application.port.out.DelegationRecertificationRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Materialises due customer review tasks. It does not mutate a delegation: overdue is a reminder,
 * not a suspension. The repository locks each grant before creating a cycle, making multi-pod
 * scheduling and a concurrent lifecycle change deterministic.
 */
@ApplicationScoped
class DelegationRecertificationJob(
    private val grants: DelegationRepository,
    private val cycles: DelegationRecertificationRepository,
    private val clock: Clock,
    private val domainMetrics: DomainMetrics,
) {
    private var liveness: WorkflowLivenessRecorder? = null

    /** Seeds the scheduler heartbeat before the first review is due. */
    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(cron = "{openbank.delegation.recertification.cron}", identity = "delegation-recertification-sweep")
    suspend fun materializeDueReviews() {
        val now = OffsetDateTime.now(clock)
        try {
            val created = grants.findActiveWithRecertificationAudience().count { grant ->
                cycles.createDueIfNeeded(grant.id, now) != null
            }
            liveness?.recordSuccess()
            if (created > 0) Log.infof("delegation.recertification.sweep due=%d", created)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.errorf(e, "delegation.recertification.sweep FAILED threshold=%s", now)
        }
    }

    companion object {
        private const val WORKFLOW_NAME = "delegation-recertification-sweep"
        private val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
