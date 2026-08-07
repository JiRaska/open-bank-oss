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
 * One scheduled enrolment run. Thin by design — see [CampaignEnrolmentSweepWorkflow].
 *
 * The activity timeout is generous compared to a journey step because this call fans out over a
 * whole segment: it evaluates the audience and starts one journey per newly-qualifying party, so its
 * duration grows with the segment rather than being a single send.
 */
class CampaignEnrolmentSweepWorkflowImpl : CampaignEnrolmentSweepWorkflow {

    private val activities: CampaignEnrolmentSweepActivities = Workflow.newActivityStub(
        CampaignEnrolmentSweepActivities::class.java,
        ActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofMinutes(SCHEDULE_TO_CLOSE_MINUTES))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(MAX_ATTEMPTS)
                    .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                    .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                    .build(),
            )
            .build(),
    )

    override fun sweep(campaignId: UUID): SweepOutcome = activities.enrolDueParties(campaignId)

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 10L
        private const val BACKOFF_COEFFICIENT = 2.0

        /**
         * Enrolment walks the whole segment, so this is sized for an audience rather than a send.
         * Retries are bounded and the schedule's overlap policy is SKIP, so a run that genuinely
         * needs longer is dropped rather than piling up behind itself.
         */
        private const val SCHEDULE_TO_CLOSE_MINUTES = 30L
    }
}
