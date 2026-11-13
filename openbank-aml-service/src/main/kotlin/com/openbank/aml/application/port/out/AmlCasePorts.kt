// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.port.out

import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.aml.domain.model.ScreeningType
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.util.UUID

/**
 * Outbound persistence port for the AML case aggregate.
 *
 * [save] and [update] persist the aggregate **and** its domain event ([event]) in a single
 * transaction (the transactional-outbox pattern, ADR-0050): either both the case row and the outbox
 * row commit, or neither does. The dispatcher then drains the outbox to Kafka asynchronously, so a
 * crash between the DB commit and the Kafka publish can never lose — or double-emit — an event.
 */
interface AmlCaseRepository {

    suspend fun save(amlCase: AmlCase, event: OutboxMessage): AmlCase

    suspend fun findById(caseId: UUID): AmlCase?

    suspend fun findByIdempotencyKey(idempotencyKey: String): AmlCase?

    suspend fun list(
        status: AmlCaseStatus?,
        partyId: UUID?,
        screeningType: ScreeningType?,
        limit: Int,
        offset: Int,
    ): List<AmlCase>

    suspend fun update(amlCase: AmlCase, event: OutboxMessage): AmlCase

    /**
     * Cases whose `party_id` is a copy of `account_id` — i.e. never resolved to a real party
     * (#3413). Bounded by [limit]; the sweep re-runs.
     */
    suspend fun findUnresolvedParty(limit: Int): List<Pair<UUID, UUID>>

    /** Point [caseId] at its real owning party. */
    suspend fun resolveParty(caseId: UUID, partyId: UUID)

    /** How many cases still carry an account id in `party_id`. Published as a gauge. */
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
