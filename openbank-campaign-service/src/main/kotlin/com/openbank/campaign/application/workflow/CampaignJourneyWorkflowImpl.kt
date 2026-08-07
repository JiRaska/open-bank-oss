// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

/**
 * Journey execution (ADR-0200 D1/D2): each step is an activity, delays are `Workflow.sleep` (not
 * scheduler rows), and a consent-revoked signal terminates the journey at once rather than at the
 * next batch boundary. The consent pull-check lives inside the delivery activity, immediately
 * before the send — pull and push together, per D2.
 */
@Suppress("MagicNumber")
class CampaignJourneyWorkflowImpl : CampaignJourneyWorkflow {

    private val activityOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofMinutes(SCHEDULE_TO_CLOSE_MINUTES))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(MAX_ATTEMPTS)
                .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                .build(),
        )
        .build()

    private val activities: CampaignJourneyActivities =
        Workflow.newActivityStub(CampaignJourneyActivities::class.java, activityOptions)

    @Volatile
    private var revoked = false

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 5L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 5L
    }

    override fun run(campaignId: UUID, partyId: UUID) {
        val definition = activities.loadDefinition(campaignId)
        for (step in definition.steps.sortedBy { it.order }) {
            if (revoked) {
                activities.markTerminated(campaignId, partyId, TerminationReason.CONSENT_REVOKED)
                return
            }
            // ADR-0200 D1 stop condition (#3585): evaluated BEFORE each step — including the first,
            // so a re-enrolled party already over the cap stops immediately — and only ever against
            // observable state (the send log's SENT count), never a fabricated signal.
            if (definition.stopCondition != null &&
                definition.stopCondition.reachedBy(activities.sendsSoFar(campaignId, partyId))
            ) {
                activities.markTerminated(campaignId, partyId, TerminationReason.STOPPED_MAX_SENDS)
                return
            }
            if (step.delaySeconds > 0) {
                // Await the delay OR the revocation signal, whichever comes first — a 30-day
                // journey reacts to revocation at signal time, not at its next step.
                val slept = Workflow.await(Duration.ofSeconds(step.delaySeconds)) { revoked }
                if (!slept && revoked || revoked) {
                    activities.markTerminated(campaignId, partyId, TerminationReason.CONSENT_REVOKED)
                    return
                }
            }
            // ADR-0200 D1 branch condition (#3585). Evaluated AFTER the delay, not before it: the
            // fact it reads is the previous step's delivery status, and the delay is precisely the
            // window in which that outcome comes back. A step whose condition does not hold is
            // SKIPPED — the journey continues to the next step — which is what makes this a branch
            // rather than a second kind of stop condition.
            //
            // Replay safety: this whole block is unreachable when `step.condition` is null, and a
            // step serialized before the field existed deserializes to null (CampaignStep.condition
            // defaults, pinned by JourneyDefinitionLegacyShapeTest). An in-flight journey replays
            // its loadDefinition result out of history, so it can never enter here and can never
            // emit a command its history does not contain.
            if (step.condition != null &&
                !step.condition.holdsFor(activities.previousDeliveryStatus(campaignId, partyId, step.order))
            ) {
                activities.skipStep(campaignId, partyId, step.order)
                continue
            }
            when (activities.deliverStep(campaignId, partyId, step.order)) {
                StepOutcome.SENT -> activities.advanceStep(campaignId, partyId, step.order)
                StepOutcome.SUPPRESSED -> {
                    activities.markTerminated(campaignId, partyId, TerminationReason.SUPPRESSED)
                    return
                }
            }
        }
        activities.markCompleted(campaignId, partyId)
    }

    override fun consentRevoked() {
        revoked = true
    }
}
