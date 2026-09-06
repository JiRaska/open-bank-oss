// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.port.out

import com.openbank.onboarding.domain.model.BusinessFunnelStage
import com.openbank.onboarding.domain.model.BusinessOnboardingRecord
import java.time.Instant
import java.util.UUID

/** Outbound persistence port for the business funnel read model (ADR-0284 D6). */
interface BusinessOnboardingRepository {

    /**
     * Upsert by case id, ignoring an event OLDER than the row it would overwrite.
     *
     * Kafka guarantees order within a partition and the case id is the partition key, so
     * out-of-order delivery needs a redelivery or a replay to happen at all — but when it does, a
     * blind upsert walks the board backwards: a replayed `SIGNER_INVITED` would drag a live
     * customer back into AWAITING_SIGNATURES, and the operator would chase a case that is done.
     * The guard is [Instant] on the event, not arrival time.
     */
    suspend fun upsert(record: BusinessOnboardingRecord, eventAt: Instant)

    suspend fun findByCaseId(caseId: UUID): BusinessOnboardingRecord?

    suspend fun listByStage(stage: BusinessFunnelStage, page: Int, size: Int): List<BusinessOnboardingRecord>

    suspend fun listAll(page: Int, size: Int): List<BusinessOnboardingRecord>

    suspend fun countAll(): Long

    suspend fun countByStage(stage: BusinessFunnelStage): Long

    /**
     * GDPR Art. 17: the initiator or a signer exercised erasure. The case row keeps the entity's
     * register facts — a company is not a data subject — but every natural-person reference goes,
     * which for this projection means the initiator id and the review reason (an operator note that
     * can name a person).
     */
    suspend fun anonymizeParty(partyId: UUID)
}
