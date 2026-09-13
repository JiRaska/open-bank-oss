// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.workflow

import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Registers the case-timers worker (ADR-0284), mirroring lending's registrar: switched by
 * `openbank.kyb.worker.enabled` (rules.yaml: temporal_worker_switch_naming) so a @QuarkusTest
 * with no Temporal frontend boots clean, and the API-fuzz harness can derive the switch name.
 */
@ApplicationScoped
class BusinessOnboardingWorkerRegistrar(
    @param:ConfigProperty(name = "openbank.kyb.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @param:ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-kyb-onboarding")
    private val taskQueue: String,
    private val workflowClient: WorkflowClient,
    private val activities: BusinessOnboardingTimerActivitiesImpl,
) {

    private val log = Logger.getLogger(BusinessOnboardingWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!workerEnabled) {
            log.info("Temporal kyb worker disabled (openbank.kyb.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal kyb worker on task queue '%s'", taskQueue)
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(BusinessOnboardingTimersWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal kyb worker started on task queue '%s'", taskQueue)
    }
}
