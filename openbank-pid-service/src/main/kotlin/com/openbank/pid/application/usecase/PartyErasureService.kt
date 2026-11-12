// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.ErasePartyUseCase
import com.openbank.pid.application.port.`in`.ErasureOutcome
import com.openbank.pid.application.port.out.PartyAnonymisation
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.application.port.out.VerificationCaseRepository
import com.openbank.pid.domain.model.ExternalIdType
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * GDPR Art. 17 for the identity registry (#15755, ADR-0118 section 3).
 *
 * Same rule kyc-service applies: anonymise in place NOW, keep the shell row stamped `erased_at`
 * (the AML record-keeping clock, ADR-0118 section 5), never keep a personal column. What pid holds
 * per party, what happens to it and what is NOT covered: see `docs/04-data` (Erasure).
 *
 * Every step runs on every delivery and each is idempotent, so a delivery that failed between
 * steps, or a late write, is repaired by the retry. One exception: the party's RČ blind index, which
 * finds the UNLINKED `RN:` cases carrying it, is deleted by the party step, so it is read first and
 * removed from those cases before the party step (and once more after it, for a case opened in
 * between). A retry after the party step committed can no longer read it.
 *
 * Unlinked cases are never fully anonymised: nothing stored ties their applicant positively to this
 * party, so only the erased person's identifier is removed (see `docs/04-data`, Not covered).
 * Nothing personal is logged: the party id is the only value written.
 */
@ApplicationScoped
class PartyErasureService(
    private val parties: PartyRepository,
    private val cases: VerificationCaseRepository,
    private val clock: Clock,
) : ErasePartyUseCase {

    private val log = Logger.getLogger(PartyErasureService::class.java)

    override suspend fun erase(partyId: UUID): ErasureOutcome {
        val now = clock.instant()
        val blindIndex = birthNumberBlindIndex(partyId)
        val unlinkedBefore = blindIndex?.let { cases.removeErasedIdentifier(it) } ?: 0
        // Party next: it takes the row lock and commits erased_at, after which no case can be linked
        // to this party (the link is checked under the same lock). Linked cases run after it, so every
        // link that committed before the party step is cleaned here, and a repeat delivery re-cleans
        // any linked case that is still not anonymised.
        val party = parties.anonymize(partyId, now)
        val linked = cases.anonymizeLinkedTo(partyId, now)
        val unlinkedAfter = blindIndex?.let { cases.removeErasedIdentifier(it) } ?: 0
        val anonymisedCases = unlinkedBefore + linked + unlinkedAfter
        val outcome = when {
            party == PartyAnonymisation.FIRST || anonymisedCases > 0 -> ErasureOutcome.ERASED
            party == PartyAnonymisation.REPEAT -> ErasureOutcome.REPEATED
            else -> ErasureOutcome.TOMBSTONED
        }
        log.infof(
            "GDPR Art. 17: party %s erasure outcome %s (party row: %s, verification cases: %d)",
            partyId,
            outcome,
            party,
            anonymisedCases,
        )
        return outcome
    }

    /** Null when the party is absent, already erased (its external ids are gone) or holds no RČ index. */
    private suspend fun birthNumberBlindIndex(partyId: UUID): String? =
        parties.findById(partyId)?.takeIf { it.erasedAt == null }
            ?.externalIds?.firstOrNull { it.type == ExternalIdType.BIRTH_NUMBER }?.value
}
