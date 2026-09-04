// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.workflow

import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Registers the origination-timers worker (ADR-0211 D2), mirroring the
 * domestic-payment registrar: gated separately from dispatch by
 * `openbank.lending.worker.enabled` (default true) so `@QuarkusTest` runs with no
 * real Temporal frontend can set it false and boot clean.
 */
@ApplicationScoped
class OriginationTimersWorkerRegistrar(
    @param:ConfigProperty(name = "openbank.lending.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @param:ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-lending-origination")
    private val taskQueue: String,
    private val workflowClient: WorkflowClient,
    private val activities: OriginationTimerActivitiesImpl,
) {

    private val log = Logger.getLogger(OriginationTimersWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!workerEnabled) {
            log.info("Temporal origination worker disabled (openbank.lending.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal worker on task queue '%s'", taskQueue)
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(OriginationTimersWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal origination worker started on task queue '%s'", taskQueue)
    }
}
