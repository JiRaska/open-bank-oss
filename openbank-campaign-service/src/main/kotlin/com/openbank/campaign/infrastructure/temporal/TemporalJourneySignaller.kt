// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.temporal

import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.workflow.CampaignJourneyWorkflow
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Temporal-backed journey control (ADR-0200 D1/D2). The workflow id is the idempotency key:
 * starting an already-running id is a no-op for Temporal, which is what makes re-enrolment safe.
 */
@ApplicationScoped
class TemporalJourneySignaller(
    private val workflowClient: WorkflowClient,
    @ConfigProperty(name = "openbank.temporal.task-queue", defaultValue = "openbank-campaign")
    private val taskQueue: String,
) : JourneySignaller {

    private val log = Logger.getLogger(TemporalJourneySignaller::class.java)

    override fun startJourney(campaignId: UUID, partyId: UUID) {
        val workflowId = workflowId(campaignId, partyId)
        val workflow = workflowClient.newWorkflowStub(
            CampaignJourneyWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build(),
        )
        try {
            WorkflowClient.start(workflow::run, campaignId, partyId)
            log.infof("Started journey %s", workflowId)
        } catch (e: io.temporal.client.WorkflowExecutionAlreadyStarted) {
            log.debugf(e, "Journey %s already running — idempotent no-op", workflowId)
        }
    }

    override fun signalConsentRevoked(campaignId: UUID, partyId: UUID) {
        val workflowId = workflowId(campaignId, partyId)
        try {
            val workflow = workflowClient.newWorkflowStub(CampaignJourneyWorkflow::class.java, workflowId)
            workflow.consentRevoked()
            log.infof("Signalled consent revocation to %s", workflowId)
        } catch (e: io.temporal.client.WorkflowNotFoundException) {
            // A completed or never-started journey has nothing to terminate — the signal is moot.
            log.debugf(e, "No live journey %s to signal", workflowId)
        } catch (e: io.temporal.client.WorkflowServiceException) {
            log.warnf(e, "Failed to signal journey %s", workflowId)
        }
    }

    companion object {
        fun workflowId(campaignId: UUID, partyId: UUID): String = "campaign-journey-$campaignId-$partyId"
    }
}
