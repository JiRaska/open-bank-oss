// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.transaction.infrastructure.temporal.TemporalConfig
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

/**
 * Registers the Temporal payment worker at boot — but only when the ADR-0120 P1 flag is on. With the
 * flag disabled (the default) this returns immediately and never touches the [WorkflowClient], so no
 * Temporal frontend dial happens at startup.
 */
@ApplicationScoped
class PaymentWorkerRegistrar(
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val activities: PaymentActivitiesImpl,
) {

    private val log = Logger.getLogger(PaymentWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!temporalConfig.enabled()) {
            log.info(
                "Temporal payment orchestration disabled " +
                    "(openbank.transaction.orchestration.temporal.enabled=false); skipping registration",
            )
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
