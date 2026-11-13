// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.event.AmlCaseStatusChangedEvent
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Attaches a case to the party that owns its account, and pays the one thing the party can be owed.
 *
 * A held payment's case is opened with NO party when the rail could not name the debtor, and can be
 * decided in that state: the rail must get its release or reject, and it binds the decision by the
 * case key. That decision's event carries no party, so no consumer that acts per party sees one.
 * For a BLOCK that is a debt: the party was blocked by an analyst and nobody was told. It is paid in
 * the transaction that resolves the case, by stating the BLOCK again on the status-changed event,
 * now with the party.
 *
 * Only that. The consumer that acts on it keeps ONE AML status per party and lets the last event
 * win, so a restated status is not harmless in general: a restated CLEARED could re-activate a
 * party that another case blocked in the meantime. A restated BLOCK can only block. Two rules
 * follow, and both are decided here and nowhere else:
 *
 *  - only a BLOCKED case is restated; a cleared or undecided one has nothing owed to the party;
 *  - only a case that was stored with no party is restated. A case that carried the account id in
 *    the party's place published its events all along and is resolved as it always was, silently.
 *
 * The restatement is not a second decision: `previousStatus` equals `newStatus`, and it carries no
 * case key, alert code or payment id, which are what a payment rail matches a decision on.
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
        return amlCaseRepository.resolveParty(caseId, partyId) { stored ->
            val owed = stored.partyId == null && stored.status == AmlCaseStatus.BLOCKED
            if (owed) blockRestated(stored, partyId, now) else null
        }
    }

    private fun blockRestated(blocked: AmlCase, partyId: UUID, now: Instant): OutboxMessage = OutboxMessage(
        eventId = UUID.randomUUID(),
        aggregateId = blocked.id,
        eventType = AmlCaseService.EVENT_STATUS_CHANGED,
        payload = objectMapper.writeValueAsString(
            AmlCaseStatusChangedEvent(
                caseId = blocked.id,
                partyId = partyId,
                previousStatus = blocked.status,
                newStatus = blocked.status,
                decisionReason = blocked.decisionReason,
                assignedAnalyst = blocked.assignedAnalyst,
                decidedBy = blocked.decidedBy,
                occurredAt = now,
            ),
        ),
    )
}
