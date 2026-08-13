// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.SendLogRepository
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Narrow customer-edge lookup for campaign-originated app interactions.
 *
 * It deliberately returns no campaign data. The caller needs only one fact — that this opaque
 * reference belongs to the authenticated party and originated from a PUSH handoff — before it
 * allows engagement-service to append an attributed event.
 */
@ApplicationScoped
class CampaignInteractionQuery(private val sendLog: SendLogRepository) {
    suspend fun isValidForParty(interactionRef: UUID, partyId: UUID): Boolean =
        sendLog.hasPushInteractionForParty(interactionRef, partyId)
}
