// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * One scheduled enrolment run of a recurring campaign.
 *
 * A Temporal *schedule* starts this workflow on the campaign's cadence; the workflow does one thing,
 * which is to re-evaluate the segment and enrol whoever newly qualifies. Everything that makes that
 * safe to repeat already exists: enrolment skips parties who are already enrolled, and a journey's
 * workflow id is its idempotency key (ADR-0200 D1).
 *
 * **Why a workflow rather than the schedule calling an activity directly.** A Temporal schedule can
 * only start a workflow, so this type is required rather than chosen. It stays deliberately thin —
 * no retry loop, no fan-out, no state — because a scheduled action that grows logic becomes a second
 * place where enrolment rules live, and the two drift.
 */
@WorkflowInterface
interface CampaignEnrolmentSweepWorkflow {

    /** Workflow id is `campaign-enrolment-sweep-{campaignId}-{scheduledTime}`, assigned by Temporal. */
    @WorkflowMethod
    fun sweep(campaignId: UUID): SweepOutcome
}

/**
 * What one scheduled run did.
 *
 * Returned rather than logged so it lands in the Temporal history, where an operator asking "did
 * last night's run reach anyone" can read it per execution. A run that enrolled nobody is a normal
 * outcome for a recurring campaign whose segment did not grow — it must be distinguishable from a
 * run that failed, which is why [skipped] carries its reason instead of the workflow throwing.
 */
data class SweepOutcome(val enrolled: Int, val failed: Int, val skipped: SweepSkip?)

/**
 * Why a scheduled run did no work.
 *
 * `NOT_ACTIVE` is the ordinary case, not an error: pausing a campaign pauses its schedule, but a run
 * already in flight when the pause landed still arrives here, and so does a run that races a close.
 * `SCHEDULE_EXPIRED` means the campaign's `endAt` has passed — the schedule is asking for work that
 * the definition says is over, and the sweep declines rather than enrolling anyone.
 */
enum class SweepSkip { NOT_ACTIVE, SCHEDULE_EXPIRED }

@ActivityInterface
interface CampaignEnrolmentSweepActivities {

    /**
     * Runs one enrolment pass for [campaignId], returning what it did.
     *
     * The state and expiry checks live inside the activity rather than in the workflow, because both
     * need the campaign row and a workflow must not touch a repository directly.
     */
    fun enrolDueParties(campaignId: UUID): SweepOutcome
}
