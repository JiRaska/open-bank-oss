// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.usecase.JourneyStartIntent
import com.openbank.campaign.application.usecase.JourneyStartIntentStore
import java.util.UUID

internal class InMemoryJourneyStartIntentStore : JourneyStartIntentStore {
    val pending = mutableMapOf<Pair<UUID, UUID>, JourneyStartIntent>()

    override suspend fun begin(intent: JourneyStartIntent): JourneyStartIntent =
        pending.getOrPut(intent.campaignId to intent.partyId) { intent }

    override suspend fun complete(campaignId: UUID, partyId: UUID) {
        pending.remove(campaignId to partyId)
    }

    override suspend fun claimRecovery(owner: UUID): JourneyStartIntent? = pending.values.firstOrNull()

    override suspend fun releaseRecovery(owner: UUID, intent: JourneyStartIntent) = Unit

    override suspend fun countStale(): Long = pending.size.toLong()
}
