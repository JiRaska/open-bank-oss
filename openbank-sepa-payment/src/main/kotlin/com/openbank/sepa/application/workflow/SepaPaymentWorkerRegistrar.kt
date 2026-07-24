// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class SepaPaymentWorkerRegistrar(
    @ConfigProperty(name = "openbank.sepa.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-sepa-payment")
    private val taskQueue: String,
    private val workflowClient: WorkflowClient,
    private val activities: SepaPaymentActivitiesImpl,
) {

    private val log = Logger.getLogger(SepaPaymentWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        // ADR-0120 Phase 6 (issue #1917): Temporal is the sole orchestrator, so the worker registers
        // by default in production. Worker registration is gated separately from dispatch by
        // openbank.sepa.worker.enabled (default true) so @QuarkusTest runs — which have no real Temporal
        // frontend and drive their own in-process TestWorkflowEnvironment — set it false and don't fail
        // boot on UNAVAILABLE (mirrors settlement-service / transaction-service).
        if (!workerEnabled) {
            log.info("Temporal sepa worker registration disabled (openbank.sepa.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal worker on task queue '%s'", taskQueue)
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(SepaPaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal worker started on task queue '%s'", taskQueue)
    }
}
