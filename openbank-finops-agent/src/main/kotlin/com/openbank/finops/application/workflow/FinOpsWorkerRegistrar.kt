// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.infrastructure.temporal.TemporalConfig
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger

@ApplicationScoped
class FinOpsWorkerRegistrar(
    private val temporalConfig: TemporalConfig,
    private val workflowClient: WorkflowClient,
    private val collectMetrics: CollectMetricsActivityImpl,
    private val detectAnomalies: DetectAnomaliesActivityImpl,
    private val diagnoseAndPropose: DiagnoseAndProposeActivityImpl,
) {

    private val log = Logger.getLogger(FinOpsWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!temporalConfig.enabled()) {
            log.info("Temporal worker disabled (openbank.temporal.enabled=false); skipping registration")
            return
        }
        log.infof("Registering Temporal finops-agent worker on task queue '%s'", temporalConfig.taskQueue())
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(temporalConfig.taskQueue())
        worker.registerWorkflowImplementationTypes(FinOpsAnalysisWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(collectMetrics, detectAnomalies, diagnoseAndPropose)
        factory.start()
        log.infof("Temporal finops-agent worker started on task queue '%s'", temporalConfig.taskQueue())
    }
}
