// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class CampaignWorkerRegistrar(
    @ConfigProperty(name = "openbank.campaign.worker.enabled", defaultValue = "true")
    private val workerEnabled: Boolean,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-campaign")
    private val taskQueue: String,
    private val workflowClient: WorkflowClient,
    private val activities: CampaignJourneyActivitiesImpl,
    private val sweepActivities: CampaignEnrolmentSweepActivitiesImpl,
) {

    private val log = Logger.getLogger(CampaignWorkerRegistrar::class.java)

    @Suppress("UnusedParameter")
    fun onStart(@Observes event: StartupEvent) {
        if (!workerEnabled) {
            log.info("Temporal campaign worker registration disabled (openbank.campaign.worker.enabled=false)")
            return
        }
        log.infof("Registering Temporal worker on task queue '%s'", taskQueue)
        val factory = WorkerFactory.newInstance(workflowClient)
        val worker = factory.newWorker(taskQueue)
        // Both workflow types share this queue. A schedule can only start a workflow, so an
        // unregistered sweep type would leave the schedule firing into a queue nobody serves —
        // Temporal would record the start and the run would time out, which reads as a scheduling
        // problem rather than a missing registration.
        worker.registerWorkflowImplementationTypes(
            CampaignJourneyWorkflowImpl::class.java,
            CampaignEnrolmentSweepWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities, sweepActivities)
        factory.start()
        log.infof("Temporal worker started on task queue '%s'", taskQueue)
    }
}
