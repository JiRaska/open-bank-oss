// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.libs.temporal.TemporalConfig
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Registers the Temporal payment worker at boot (ADR-0120 Phase 5: always-on after saga retirement).
 */
@ApplicationScoped
class PaymentWorkerRegistrar(
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val activities: PaymentActivitiesImpl,
    @ConfigProperty(name = "openbank.transaction.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
) {

    private val log = Logger.getLogger(PaymentWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!workerEnabled) {
            // @QuarkusTest runs replace the WorkflowClient with the in-process
            // TestWorkflowEnvironment and register a deterministic no-op worker
            // (WorkflowClientTestProducer). Registering the REAL workflow + activities on the
            // same task queue made task dispatch a two-worker lottery — transactions randomly
            // FAILED whenever the real worker won and its activities hit absent infrastructure
            // (#465). Tests set openbank.transaction.worker.enabled=false.
            log.info("Temporal payment worker registration disabled (openbank.transaction.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal payment worker on task queue '%s'", temporalConfig.taskQueue())
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(temporalConfig.taskQueue())
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        log.infof("Temporal payment worker started on task queue '%s'", temporalConfig.taskQueue())
    }
}
