// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.SendRecord
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Read-only view of what a campaign actually did, for the operator console (#2895).
 *
 * Separate from [CampaignService] on purpose: that class owns the campaign lifecycle — create,
 * submit, activate, enrol — and this one only answers questions. Keeping the query here also keeps
 * both classes under the detekt function-count threshold without raising it, which is the honest
 * reading of that rule rather than a workaround.
 */
@ApplicationScoped
class CampaignSendLogQuery(private val sendLog: SendLogRepository) {

    /**
     * Every send attempt for this campaign, newest first — **including suppressed ones**.
     *
     * The outcome is the whole point: from the enrolment side a party that was contacted and one
     * that was deliberately skipped (consent withdrawn, frequency cap, quiet hours — ADR-0200 D6)
     * look identical. Filtering this to successful deliveries would answer "who got it" while
     * losing "why didn't they", which is the question operators actually ask.
     */
    suspend fun listSends(campaignId: UUID): List<SendRecord> = sendLog.listByCampaign(campaignId)
}
