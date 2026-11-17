// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.balance.infrastructure.schedule

import com.openbank.balance.infrastructure.persistence.repository.BranchBookReconciliations
import com.openbank.libs.persistence.lock.ClusterLock
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.LocalDate

/** Reader-first: the projection cutover enables periodic retry, including after a restart. */
@ApplicationScoped
class BranchBookReconciliationScheduler(
    private val reports: BranchBookReconciliations,
    private val clock: Clock,
    private val lock: ClusterLock,
    @ConfigProperty(name = "openbank.balance.projection.enabled", defaultValue = "false")
    private val enabled: Boolean,
    private val metrics: com.openbank.libs.observability.DomainMetrics,
    @ConfigProperty(name = "openbank.balance.branch-reconciliation.interval", defaultValue = "PT1M")
    private val interval: java.time.Duration,
) {
    private var liveness: com.openbank.libs.observability.WorkflowLivenessRecorder? = null

    fun onStart(@jakarta.enterprise.event.Observes event: io.quarkus.runtime.StartupEvent) {
        if (enabled) {
            liveness =
                metrics.registerWorkflowLiveness("branch-book-reconciliation", interval)
        }
    }

    @Scheduled(
        every = "{openbank.balance.branch-reconciliation.interval:PT1M}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun reconcile() {
        if (!enabled) return
        lock.tryRunExclusively("balance.branch-book-reconciliation") {
            reports.banks().forEach { reports.run(it, LocalDate.now(clock)) }
            liveness?.recordSuccess()
        }
    }
}
