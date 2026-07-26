// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.libs.temporal.TemporalConfig
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
