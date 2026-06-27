// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.infrastructure.temporal.TemporalConfig
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

@ApplicationScoped
class DevOpsWorkerRegistrar(
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val collectSignals: CollectSignalsActivityImpl,
    private val detectFindings: DetectFindingsActivityImpl,
    private val diagnoseAndPropose: DiagnoseAndProposeActivityImpl,
) {

    private val log = Logger.getLogger(DevOpsWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!temporalConfig.enabled()) {
            log.info("Temporal worker disabled (openbank.temporal.enabled=false); skipping registration")
            return
        }
        log.infof("Registering Temporal devops-agent worker on task queue '%s'", temporalConfig.taskQueue())
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(temporalConfig.taskQueue())
        worker.registerWorkflowImplementationTypes(DevOpsAnalysisWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(collectSignals, detectFindings, diagnoseAndPropose)
        factory.start()
        log.infof("Temporal devops-agent worker started on task queue '%s'", temporalConfig.taskQueue())
    }
}
