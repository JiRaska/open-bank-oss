// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.temporal

import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.workflow.CampaignJourneyWorkflow
import com.openbank.campaign.application.workflow.DecisionJourneyWorkflow
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
    @ConfigProperty(name = "openbank.temporal.decision-task-queue", defaultValue = "openbank-campaign-decision")
    private val decisionTaskQueue: String,
) : JourneySignaller {

    private val log = Logger.getLogger(TemporalJourneySignaller::class.java)

    override fun startJourney(campaignId: UUID, partyId: UUID, type: JourneyType) {
        val workflowId = workflowId(campaignId, partyId)
        try {
            when (type) {
                JourneyType.LINEAR -> startLinear(workflowId, campaignId, partyId)
                JourneyType.DECISION_GRAPH -> startDecisionGraph(workflowId, campaignId, partyId)
            }
            log.infof("Started %s journey %s", type, workflowId)
        } catch (e: io.temporal.client.WorkflowExecutionAlreadyStarted) {
            log.debugf(e, "Journey %s already running — idempotent no-op", workflowId)
        }
    }

    private fun startLinear(workflowId: String, campaignId: UUID, partyId: UUID) {
        val workflow = workflowClient.newWorkflowStub(
            CampaignJourneyWorkflow::class.java,
            workflowOptions(workflowId, taskQueue),
        )
        WorkflowClient.start(workflow::run, campaignId, partyId)
    }

    private fun startDecisionGraph(workflowId: String, campaignId: UUID, partyId: UUID) {
        val workflow = workflowClient.newWorkflowStub(
            DecisionJourneyWorkflow::class.java,
            workflowOptions(workflowId, decisionTaskQueue),
        )
        WorkflowClient.start(workflow::run, campaignId, partyId)
    }

    private fun workflowOptions(workflowId: String, queue: String): WorkflowOptions = WorkflowOptions.newBuilder()
        .setWorkflowId(workflowId)
        .setTaskQueue(queue)
        .build()

    override fun signalConsentRevoked(campaignId: UUID, partyId: UUID) {
        signal(campaignId, partyId, "consent revocation") { it.consentRevoked() }
    }

    override fun signalCampaignPaused(campaignId: UUID, partyId: UUID) {
        signal(campaignId, partyId, "campaign pause") { it.campaignPaused() }
    }

    override fun signalCampaignResumed(campaignId: UUID, partyId: UUID) {
        signal(campaignId, partyId, "campaign resume") { it.campaignResumed() }
    }

    override fun signalCampaignClosed(campaignId: UUID, partyId: UUID) {
        signal(campaignId, partyId, "campaign close") { it.campaignClosed() }
    }

    override fun signalGoalReached(campaignId: UUID, partyId: UUID) {
        signal(campaignId, partyId, "goal reached") { it.goalReached() }
    }

    private fun signal(campaignId: UUID, partyId: UUID, control: String, invoke: (CampaignJourneyWorkflow) -> Unit) {
        val workflowId = workflowId(campaignId, partyId)
        try {
            val workflow = workflowClient.newWorkflowStub(CampaignJourneyWorkflow::class.java, workflowId)
            invoke(workflow)
            log.infof("Signalled %s to %s", control, workflowId)
        } catch (e: io.temporal.client.WorkflowNotFoundException) {
            // A completed or never-started journey has nothing to terminate — the signal is moot.
            log.debugf(e, "No live journey %s to signal", workflowId)
        } catch (e: io.temporal.client.WorkflowServiceException) {
            // Pause/close correctness is also backed by the campaign-state activity immediately
            // before a send; signals are the low-latency wake-up path, not the only control.
            log.warnf(e, "Failed to signal %s to journey %s", control, workflowId)
        }
    }

    companion object {
        fun workflowId(campaignId: UUID, partyId: UUID): String = "campaign-journey-$campaignId-$partyId"
    }
}
