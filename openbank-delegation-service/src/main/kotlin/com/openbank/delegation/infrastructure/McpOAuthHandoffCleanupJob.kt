// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure

import com.openbank.delegation.infrastructure.persistence.repository.McpOAuthHandoffStore
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class McpOAuthHandoffCleanupJob(private val handoffs: McpOAuthHandoffStore) {
    /** Retention continues while issuance is off so finished claims do not accumulate. */
    @Scheduled(
        every = "5m",
        identity = "mcp-native-handoff-retention",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun purge() {
        handoffs.purgeExpired(limit = 1_000)
    }
}
