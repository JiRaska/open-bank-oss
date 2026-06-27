// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.onboarding.application.port.out

import com.openbank.onboarding.domain.model.FunnelStage
import com.openbank.onboarding.domain.model.OnboardingRecord
import java.util.UUID

/**
 * Outbound port: persistence for the onboarding read-model (ADR-0002 hexagonal architecture).
 * Implemented by [com.openbank.onboarding.infrastructure.persistence.repository.OnboardingRepositoryImpl].
 */
interface OnboardingRepository {

    suspend fun upsert(record: OnboardingRecord)

    suspend fun findByPartyId(partyId: UUID): OnboardingRecord?

    suspend fun listByStage(stage: FunnelStage, page: Int, size: Int): List<OnboardingRecord>

    suspend fun countByStage(stage: FunnelStage): Long

    suspend fun listAll(page: Int, size: Int): List<OnboardingRecord>

    suspend fun countAll(): Long

    /** Returns records stuck in early KYC stages since before [cutoff]. Used by abandoned-registration cleanup. */
    suspend fun listStuckBefore(stages: List<FunnelStage>, cutoff: java.time.Instant): List<OnboardingRecord>
}
