// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * Isolated Temporal contract for explicit decision graphs.
 *
 * It intentionally has its own workflow type and task queue. The signatures of its signals match
 * [CampaignJourneyWorkflow], so lifecycle controls remain uniform while the workflow code can be
 * rolled back without handing graph histories to an older binary.
 */
@WorkflowInterface
interface DecisionJourneyWorkflow {

    @WorkflowMethod
    fun run(campaignId: UUID, partyId: UUID)

    @SignalMethod
    fun consentRevoked()

    @SignalMethod
    fun campaignPaused()

    @SignalMethod
    fun campaignResumed()

    @SignalMethod
    fun campaignClosed()

    @SignalMethod
    fun goalReached()
}
