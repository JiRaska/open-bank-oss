// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.application.port.out

import com.openbank.kyc.domain.model.KycCase
import com.openbank.kyc.domain.model.KycCaseStatus
import com.openbank.kyc.domain.model.KycEvent
import java.time.Instant
import java.util.UUID

/**
 * Outbound persistence port for the KYC case aggregate (ADR-0002 hexagonal architecture).
 * Implemented by [com.openbank.kyc.infrastructure.persistence.KycRepository].
 */
@Suppress("TooManyFunctions")
interface KycCaseRepository {

    suspend fun save(case: KycCase): KycCase

    /**
     * Persists [case] AND [event] in one transaction (issue #4007). This — not a Kafka emitter —
     * is how a KYC lifecycle event leaves the service: the case row and its `kyc_outbox` entry
     * commit together, or neither does, and the dispatcher relays the entry afterwards.
     *
     * The event-free [save] above stays for the paths that legitimately publish nothing (test
     * fixtures, pact provider states). Prefer this one for anything a consumer must hear about.
     */
    suspend fun save(case: KycCase, event: KycEvent): KycCase

    suspend fun findById(id: UUID): KycCase?

    /** Most recent case for a party regardless of status — used for history lookups. */
    suspend fun findByPartyId(partyId: UUID): KycCase?

    /**
     * The party's current **active** (non-terminal) case, or null if none is in flight.
     * Backed by the partial unique index `uq_kyc_cases_active_party` (V5), so at most one row
     * matches. This is the right lookup for the "does an open case already exist?" question.
     */
    suspend fun findActiveByPartyId(partyId: UUID): KycCase?

    suspend fun listAll(page: Int, size: Int): List<KycCase>

    /** Filter by [status]. Used by the onboarding cockpit funnel view (ADR-0068). */
    suspend fun listByStatus(status: KycCaseStatus, page: Int, size: Int): List<KycCase>

    suspend fun countAll(): Long

    /** Count cases in a given [status]. Used for funnel KPI tiles (ADR-0068). */
    suspend fun countByStatus(status: KycCaseStatus): Long

    suspend fun update(case: KycCase): KycCase

    /** Transactional-outbox counterpart of [update] — see [save] with a [KycEvent]. */
    suspend fun update(case: KycCase, event: KycEvent): KycCase

    suspend fun anonymizeByPartyId(partyId: UUID, now: Instant)

    /** Deletes KYC cases whose PII was erased and the AML hold period ([cutoff]) has expired (ADR-0118 §5). */
    suspend fun deleteErasedCasesOlderThan(cutoff: Instant): Long
}
