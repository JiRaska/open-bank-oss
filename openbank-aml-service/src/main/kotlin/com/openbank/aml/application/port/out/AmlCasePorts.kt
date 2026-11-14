// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.domain.error.ResourceConflictException
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/** The case was changed by someone else between being read and being written; read it again and retry. */
class CaseChangedConcurrentlyException(caseId: UUID) :
    ResourceConflictException("AML case $caseId changed while it was being decided; read it again and retry")

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
     * Stores a new case and the event [event] builds from the case AS STORED. When the case names a
     * party, the party is attached the one way every writer attaches one (see [resolveParty]): under
     * the party's lock, against the erasure record, so a case for an erased party is stored, and
     * announced, already anonymised.
     */
    suspend fun save(amlCase: AmlCase, event: (AmlCase) -> OutboxMessage): AmlCase

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

    /**
     * Writes the transition of [amlCase] and its [event] in one transaction, under a lock on the case.
     * [event] was built from the case as the caller read it; if the case's party has been resolved
     * since, the event would name nobody for a decision that now has a party to reach, so the write
     * is refused with [CaseChangedConcurrentlyException] and nothing changes.
     */
    suspend fun update(amlCase: AmlCase, event: OutboxMessage): AmlCase

    /**
     * Cases that know their account but not their party: `party_id` is missing, or is a copy of
     * `account_id` (rows from before a missing party could be stored, #3413). Bounded by [limit] and
     * ordered least recently attempted first, so cases that cannot be resolved (an account the
     * authority does not know) cannot occupy every tick's batch ahead of ones that can.
     */
    suspend fun findUnresolvedParty(limit: Int): List<Pair<UUID, UUID>>

    /**
     * Points [caseId] at its real owning party, in one transaction and under a lock on the case.
     *
     *  - [owed] is given the case AS IT WAS STORED, before the party is set, and returns the event
     *    the party is owed for it, or `null` for none. The event is written to the outbox in the same
     *    transaction, so it cannot be lost between the two writes.
     *  - If the party has been erased ([anonymizeByPartyId] recorded it), the case is anonymised
     *    exactly as that erasure would have anonymised it.
     *
     * `false`, and nothing written, when the case is gone or already names a party.
     */
    suspend fun resolveParty(caseId: UUID, partyId: UUID, owed: (AmlCase) -> OutboxMessage?): Boolean

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
     *
     * A case need not name the party yet to be the party's, and such a case cannot be found here.
     * The same transaction therefore RECORDS the erasure, and [resolveParty] anonymises a case it
     * attaches to the party later. The record holds the party id and a time, nothing else.
     */
    suspend fun anonymizeByPartyId(partyId: UUID, erasedAt: Instant): Int
}

/** ADR-0374 point 3: the write side of a held payment's four-eyes decision. */
interface HoldDecisionRepository {
    /**
     * Writes a held payment's case [decided] — a proposal, or its confirmation — and [event], if any,
     * in one transaction under a lock on the case. [readAs] is the case as the caller read it: if the
     * stored status, party or proposal differs from it, someone changed the case in between (another
     * proposal replaced the one being confirmed), so nothing is written and
     * [CaseChangedConcurrentlyException] is thrown.
     */
    suspend fun writeHoldDecision(readAs: AmlCase, decided: AmlCase, event: OutboxMessage?): AmlCase
}
