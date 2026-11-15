// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.out.PartyMandateRepository
import com.openbank.party.domain.model.MandateStatus
import com.openbank.party.domain.model.PartyActor
import com.openbank.party.domain.model.PartyEvents
import com.openbank.party.domain.model.PartyMandate
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Revokes the representation mandates a kyb business-onboarding case granted, when that case ends
 * without the entity being onboarded (#16015).
 *
 * kyb-service grants a mandate per signer at SIGNED, and SIGNED is not terminal: the applicant can
 * still abandon, an idle timer can abandon, an operator can reject. Each mandate it grants carries
 * `evidenceRef = "kyb-case:<caseId>:signature:<signatureRef>"`, so the case's mandates are exactly
 * the ACTIVE mandates on the entity whose evidenceRef starts with `kyb-case:<caseId>:`. The trailing
 * colon matters: without it the case id would be a prefix match on nothing else today, but the
 * boundary is what makes the selection a statement about one case.
 *
 * Idempotent by construction: a redelivered event finds no ACTIVE mandate left and does nothing.
 * Driven by `BUSINESS_ONBOARDING_ABANDONED` / `_REJECTED` on `openbank.kyb.events` rather than a
 * kyb-service call, so no machine is given a mandate-write grant (rules.yaml:
 * shared_m2m_matrix_write_grants).
 */
@ApplicationScoped
class KybCaseMandateRevocation(private val mandateRepo: PartyMandateRepository, private val clock: Clock) {
    /** Returns the mandates this call revoked; empty when there was nothing ACTIVE left to revoke. */
    suspend fun revokeForEndedCase(entityPartyId: UUID, caseId: UUID, eventType: String): List<PartyMandate> {
        val prefix = evidencePrefix(caseId)
        return mandateRepo.findByPrincipal(entityPartyId)
            .filter { it.status == MandateStatus.ACTIVE && it.evidenceRef?.startsWith(prefix) == true }
            .map { mandate ->
                val now = Instant.now(clock)
                val revoked = mandate.copy(
                    status = MandateStatus.REVOKED,
                    revokedAt = now,
                    revokeReason = "kyb case $caseId ended: $eventType",
                    updatedAt = now,
                )
                mandateRepo.update(revoked, PartyEvents.mandateRevoked(revoked, now, PartyActor.system(MECHANISM)))
            }
    }

    companion object {
        const val MECHANISM = "kyb-events-consumer"

        fun evidencePrefix(caseId: UUID): String = "kyb-case:$caseId:"
    }
}
