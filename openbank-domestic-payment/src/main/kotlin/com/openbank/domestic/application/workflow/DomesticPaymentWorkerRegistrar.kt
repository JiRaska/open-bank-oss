// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class DomesticPaymentWorkerRegistrar(
    @ConfigProperty(name = "openbank.domestic.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val taskQueue: String,
    private val workflowClient: WorkflowClient,
    private val activities: DomesticPaymentActivitiesImpl,
) {

    private val log = Logger.getLogger(DomesticPaymentWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        // ADR-0120 Phase 6 (issue #1917): Temporal is the sole orchestrator, so the worker registers by
        // default in production. Worker registration is gated separately from dispatch by
        // openbank.domestic.worker.enabled (default true) so @QuarkusTest runs — which have no real
        // Temporal frontend and drive their own in-process TestWorkflowEnvironment — set it false and
        // don't fail boot on UNAVAILABLE (mirrors settlement / sepa-payment / transaction-service).
        if (!workerEnabled) {
            log.info("Temporal domestic worker registration disabled (openbank.domestic.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal worker on task queue '%s'", taskQueue)
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal worker started on task queue '%s'", taskQueue)
    }
}
