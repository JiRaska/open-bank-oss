// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.application.usecase.BusinessSigningService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.time.Duration

/**
 * ADR-0312: an approval request past `expiresAt` becomes EXPIRED and emits APPROVAL_EXPIRED —
 * PENDING requests, and APPROVED payments nobody claimed in time. Expired means nothing executes:
 * the release claim refuses it independently, so this sweep only makes the state visible.
 *
 * `suspend fun` (rules.yaml: scheduled_methods): a plain @Scheduled method runs without a Vert.x
 * context and every reactive Panache call would throw HR000068. Proven against the REAL cron by
 * BusinessApprovalExpirySweepIT, never by calling this method directly.
 */
@ApplicationScoped
class BusinessApprovalExpiryJob {

    @Inject
    lateinit var service: BusinessSigningService

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    fun registerLiveness(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Suppress("TooGenericExceptionCaught") // a sweep must survive and log a failed run
    @Scheduled(cron = "{openbank.delegation.business-signing.expiry-cron}", identity = "business-approval-expiry-sweep")
    suspend fun sweep() {
        val expired = try {
            service.expireDue()
        } catch (e: Exception) {
            Log.errorf(e, "business-approval.expiry.sweep FAILED")
            return
        }
        liveness?.recordSuccess()
        if (expired > 0) Log.infof("business-approval.expiry.sweep expired=%d", expired)
    }

    private companion object {
        const val WORKFLOW_NAME = "business-approval-expiry-sweep"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
