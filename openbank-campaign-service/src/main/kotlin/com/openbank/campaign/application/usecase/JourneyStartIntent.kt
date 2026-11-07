// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.domain.model.ContentVariant
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.ExperimentCohort
import java.time.Instant
import java.util.UUID

/** Persisted before Temporal starts, and deleted only after the ACTIVE enrolment is committed. */
data class JourneyStartIntent(
    val campaignId: UUID,
    val partyId: UUID,
    val enrolmentId: UUID,
    val journeyType: JourneyType,
    val source: JourneyStartSource,
    val contentVariant: ContentVariant?,
    val createdAt: Instant,
) {
    fun enrolment(): Enrolment = Enrolment(
        id = enrolmentId,
        campaignId = campaignId,
        partyId = partyId,
        state = EnrolmentState.ACTIVE,
        currentStep = 0,
        startedAt = createdAt,
        completedAt = null,
        experimentCohort = ExperimentCohort.TREATMENT,
        contentVariant = contentVariant,
    )
}

enum class JourneyStartSource { DIRECT, TRIGGER }

interface JourneyStartIntentStore {
    /** Conflict returns the original identity and content choice, never a fresh revision. */
    suspend fun begin(intent: JourneyStartIntent): JourneyStartIntent

    suspend fun complete(campaignId: UUID, partyId: UUID)

    /** Claims at most one stale intent through the same global admission budget as bulk runs. */
    suspend fun claimRecovery(owner: UUID): JourneyStartIntent?

    suspend fun releaseRecovery(owner: UUID, intent: JourneyStartIntent)

    /** -1 is reserved for an unreadable query in the observation layer. */
    suspend fun countStale(): Long
}
