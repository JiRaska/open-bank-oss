// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.event.AmlCaseStatusChangedEvent
import com.openbank.aml.domain.model.AmlCase
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Attaches a case to the party that owns its account, and pays what the party was owed meanwhile.
 *
 * A case can be decided before it names a party: a held payment's case is opened without one when
 * the rail could not name the debtor, and the rail must still get its release or reject. That
 * decision's event carries no party (`partyId` is null), so there is nothing in it for a consumer
 * that acts per party to act on. A BLOCK must not end there. When the party
 * becomes known, the case's status is STATED AGAIN on the same event type, now with the real party,
 * in the transaction that resolves the case — so the decision reaches the party exactly once it
 * has someone to reach, and cannot be lost between the two writes.
 *
 * The restatement is not a second decision. `previousStatus` equals `newStatus`, and it carries no
 * case key, alert code or payment id: those are what a payment rail matches a decision on, and a
 * rail has already had this one.
 *
 * It is published for every resolved case, decided or not. A consumer that keeps a per-party record
 * of cases learns from it whose case this is; one that acts only on CLEARED or BLOCKED ignores the
 * rest, as it does for any status change.
 */
@ApplicationScoped
class CasePartyResolver(
    private val amlCaseRepository: AmlCaseRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /** `false` when there was nothing to resolve: the case is gone or already names a party. */
    suspend fun resolve(caseId: UUID, partyId: UUID): Boolean {
        val now = Instant.now(clock)
        return amlCaseRepository.resolveParty(caseId, partyId) { resolved -> restatement(resolved, now) }
    }

    private fun restatement(resolved: AmlCase, now: Instant): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = resolved.id,
        eventType = AmlCaseService.EVENT_STATUS_CHANGED,
        payload = objectMapper.writeValueAsString(
            AmlCaseStatusChangedEvent(
                caseId = resolved.id,
                partyId = checkNotNull(resolved.partyId) { "a resolved case names its party" },
                previousStatus = resolved.status,
                newStatus = resolved.status,
                decisionReason = resolved.decisionReason,
                assignedAnalyst = resolved.assignedAnalyst,
                decidedBy = resolved.decidedBy,
                occurredAt = now,
            ),
        ),
    )
}
