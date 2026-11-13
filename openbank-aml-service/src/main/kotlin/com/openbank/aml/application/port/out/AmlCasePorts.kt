// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/** A case that gives up its idempotency key to the case being saved, and the key it is moved to. */
data class DisplacedCase(val caseId: UUID, val movedToKey: String)

/**
 * Outbound persistence port for the AML case aggregate.
 *
 * [save] and [update] persist the aggregate **and** its domain event ([event]) in a single
 * transaction (the transactional-outbox pattern, ADR-0050): either both the case row and the outbox
 * row commit, or neither does. The dispatcher then drains the outbox to Kafka asynchronously, so a
 * crash between the DB commit and the Kafka publish can never lose — or double-emit — an event.
 */
interface AmlCaseRepository {

    /**
     * With [displacing], [amlCase] is opened under a key another case holds: in the SAME transaction
     * that case is moved to [DisplacedCase.movedToKey] first. Either the key changes hands and the
     * new case exists with its event, or nothing changed.
     */
    suspend fun save(amlCase: AmlCase, event: OutboxMessage, displacing: DisplacedCase? = null): AmlCase

    suspend fun findById(caseId: UUID): AmlCase?

    suspend fun findByIdempotencyKey(idempotencyKey: String): AmlCase?

    suspend fun list(
        status: AmlCaseStatus?,
        partyId: UUID?,
        partyResolved: Boolean?,
        screeningType: ScreeningType?,
        limit: Int,
        offset: Int,
    ): List<AmlCase>

    suspend fun update(amlCase: AmlCase, event: OutboxMessage): AmlCase

    /**
     * Cases that know their account but not their party: `party_id` is missing, or is a copy of
     * `account_id` (rows from before a missing party could be stored, #3413). Bounded by [limit] and
     * ordered least recently attempted first, so cases that cannot be resolved (an account the
     * authority does not know) cannot occupy every tick's batch ahead of ones that can.
     */
    suspend fun findUnresolvedParty(limit: Int): List<Pair<UUID, UUID>>

    /** Point [caseId] at its real owning party. */
    suspend fun resolveParty(caseId: UUID, partyId: UUID)

    /** Records that a resolution of [caseId] was tried at [at] and did not succeed. */
    suspend fun markPartyResolutionAttempted(caseId: UUID, at: Instant)

    /** How many cases [findUnresolvedParty] would still select. Published as a gauge. */
    suspend fun countUnresolvedParty(): Long

    /**
     * Anonymizes PII in all AML cases for the given party (GDPR Art. 17 right of erasure).
     *
     * Sets [AmlCase.customerReference] to `"ERASED-<partyId>"` and nulls [AmlCase.matchedEntity]
     * and [AmlCase.alertDetail] so that no personal data is retained. Returns the number of rows
     * affected. Idempotent: re-running after a previous erasure leaves rows unchanged.
     */
    suspend fun anonymizeByPartyId(partyId: UUID): Int
}
