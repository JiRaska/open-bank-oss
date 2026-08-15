// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * One party's journey through a campaign (ADR-0200 D1). Workflow id
 * `campaign-journey-{campaignId}-{partyId}` is the idempotency key against double-enrolment.
 */
@WorkflowInterface
interface CampaignJourneyWorkflow {

    @WorkflowMethod
    fun run(campaignId: UUID, partyId: UUID)

    /** ADR-0200 D2 push mechanism: a revoked consent terminates the journey mid-flight. */
    @SignalMethod
    fun consentRevoked()

    /** Stops future steps without destroying the journey; resume releases the wait. */
    @SignalMethod
    fun campaignPaused()

    @SignalMethod
    fun campaignResumed()

    /** A terminal operator stop. */
    @SignalMethod
    fun campaignClosed()

    /** The observed product outcome has happened; no later campaign step may be sent. */
    @SignalMethod
    fun goalReached()
}
