// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.infrastructure.persistence.repository.McpOAuthHandoffStore
import com.openbank.libs.observability.DomainMetrics
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

@Startup
@ApplicationScoped
class McpOAuthHandoffCleanupJob(private val handoffs: McpOAuthHandoffStore, domainMetrics: DomainMetrics) {
    private val liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, Duration.ofMinutes(5))

    /** Retention continues while issuance is off so finished claims do not accumulate. */
    @Scheduled(
        every = "5m",
        identity = WORKFLOW_NAME,
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun purge() {
        handoffs.purgeExpired(limit = 1_000)
        liveness.recordSuccess()
    }

    private companion object {
        const val WORKFLOW_NAME = "mcp-native-handoff-retention"
    }
}
