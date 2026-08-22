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

    /**
     * Of [partyIds], the subset that has **any** KYC case — terminal, active or erased-but-retained.
     *
     * Deliberately not [findActiveByPartyId]'s question. The reconciliation behind this
     * (issue #5698) asks whether the party was ever projected into KYC at all, so a REJECTED or
     * APPROVED case counts as present: those parties were handled, and a party whose case closed is
     * not the stranded-onboarding defect. Only the total absence of a row is.
     *
     * Batched rather than a lookup per id — the caller scans the whole party register on every
     * tick, and the per-id shape would make a monitoring job the heaviest reader of kyc-db.
     * An empty [partyIds] returns an empty set without touching the database.
     *
     * [partyIds] is UNBOUNDED by contract: the caller's candidate set is limited only by its own
     * page cap, so an implementation must not assume it fits one statement. The JPA implementation
     * chunks at `KycRepository.ID_BATCH_SIZE`, keeping the bind-parameter count of any single
     * statement constant no matter how large the register grows — `IN :ids` expands to one bind
     * per id, and PostgreSQL's wire protocol caps a statement at 65,535 of them.
     */
    suspend fun findPartyIdsWithAnyCase(partyIds: Collection<UUID>): Set<UUID>

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
