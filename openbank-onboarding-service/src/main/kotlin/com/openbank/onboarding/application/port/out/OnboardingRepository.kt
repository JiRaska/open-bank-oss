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

    /**
     * Records that [credentialId] is enrolled for [partyId] and returns the party's resulting
     * total number of distinct enrolled credentials.
     *
     * **Idempotent by construction (#6248).** Re-recording a credential already present is a
     * no-op and returns the unchanged total, so a replayed `DEVICE_ENROLLED` converges instead
     * of inflating `deviceCount`. This is what makes any backfill of the enrolments lost to
     * #4353 safe to run more than once — the previous `deviceCount + 1` did not.
     */
    suspend fun recordDeviceEnrolment(partyId: UUID, credentialId: String, enrolledAt: java.time.Instant): Int

    suspend fun findByPartyId(partyId: UUID): OnboardingRecord?

    suspend fun listByStage(stage: FunnelStage, page: Int, size: Int): List<OnboardingRecord>

    suspend fun countByStage(stage: FunnelStage): Long

    suspend fun listAll(page: Int, size: Int): List<OnboardingRecord>

    suspend fun countAll(): Long

    /** Returns records stuck in early KYC stages since before [cutoff]. Used by abandoned-registration cleanup. */
    suspend fun listStuckBefore(stages: List<FunnelStage>, cutoff: java.time.Instant): List<OnboardingRecord>

    /**
     * GDPR Art. 17 — Right to Erasure.
     * Anonymises the onboarding read-model row for the given party by overwriting PII fields
     * (legalName, email) with sentinel values.  The row is retained so funnel metrics stay
     * consistent; only identifiable data is removed.
     */
    suspend fun eraseByPartyId(partyId: UUID)
}
