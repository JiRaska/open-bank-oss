// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
    @ConfigProperty(name = "openbank.temporal.enabled", defaultValue = "false")
    private val enabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-domestic-payments")
    private val taskQueue: String,
    private val workflowClient: WorkflowClient,
    private val activities: DomesticPaymentActivitiesImpl,
) {

    private val log = Logger.getLogger(DomesticPaymentWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!enabled) {
            log.info("Temporal worker disabled (openbank.temporal.enabled=false); skipping registration")
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
