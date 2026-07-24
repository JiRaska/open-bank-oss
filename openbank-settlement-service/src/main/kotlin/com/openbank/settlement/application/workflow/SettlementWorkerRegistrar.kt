// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.infrastructure.temporal.TemporalConfig
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class SettlementWorkerRegistrar(
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val activities: SettlementActivitiesImpl,
    @ConfigProperty(name = "openbank.settlement.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
) {

    private val log = Logger.getLogger(SettlementWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        // ADR-0120 Phase 6 (issue #1917): Temporal is the sole settlement orchestrator, so the worker
        // registers by default in production. Worker registration is gated separately from dispatch by
        // openbank.settlement.worker.enabled (default true) so @QuarkusTest runs — which have no real
        // Temporal frontend to poll and drive their own in-process TestWorkflowEnvironment — set it
        // false and don't fail boot on `UNAVAILABLE` (mirrors transaction-service's worker.enabled).
        if (!workerEnabled) {
            log.info("Temporal settlement worker registration disabled (openbank.settlement.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal settlement worker on task queue '%s'", temporalConfig.taskQueue())
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(temporalConfig.taskQueue())
        worker.registerWorkflowImplementationTypes(SettlementWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal settlement worker started on task queue '%s'", temporalConfig.taskQueue())
    }
}
