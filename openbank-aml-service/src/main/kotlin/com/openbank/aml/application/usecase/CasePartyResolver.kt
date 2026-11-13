// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.application.port.out.AmlCaseRepository
import com.openbank.aml.domain.event.AmlCaseStatusChangedEvent
import com.openbank.aml.domain.model.AmlCase
import com.openbank.aml.domain.model.AmlCaseStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Attaches a case to the party that owns its account, and pays the one thing the party can be owed.
 *
 * A case can be decided before its party is established ([AmlCase.partyResolved]). A held payment's
 * case is opened with no party when the rail could not name the debtor; other rails file the debtor
 * ACCOUNT id where the party belongs. Either way a decision on it is published with nothing a
 * consumer that acts per party can use: no party, or an account id the party authority cannot find.
 * For a BLOCK that is a debt: an analyst blocked the party and nobody was told. It is paid in the
 * transaction that resolves the case, by stating the BLOCK again on the status-changed event, now
 * with the party.
 *
 * Only a BLOCK, and that is decided here and nowhere else. The consumer that acts on it keeps ONE
 * AML status per party and lets the last event win, so a restated status is not harmless in
 * general: a restated CLEARED could re-activate a party that another case blocked in the meantime.
 * A restated BLOCK can only block. A case that is not BLOCKED when it is resolved owes nothing.
 *
 * At most once per case: the repository resolves a case only while its party is not established,
 * under a lock on the row, and resolving establishes it.
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
            val owed = stored.status == AmlCaseStatus.BLOCKED && !stored.partyEstablished
            if (owed) blockRestated(stored, partyId, now) else null
        }
    }

    private fun blockRestated(blocked: AmlCase, partyId: UUID, now: Instant): OutboxMessage = OutboxMessage(
        eventId = Ids.newId(),
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
