// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.ErasePartyUseCase
import com.openbank.pid.application.port.`in`.ErasureOutcome
import com.openbank.pid.application.port.out.PartyAnonymisation
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.application.port.out.VerificationCaseRepository
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.util.UUID

/**
 * GDPR Art. 17 for the identity registry (#15755, ADR-0118 section 3).
 *
 * Same rule kyc-service applies: anonymise in place NOW, keep the shell row stamped `erased_at`
 * (the AML record-keeping clock, ADR-0118 section 5), never keep a personal column. What pid holds
 * per party and what happens to it: see `docs/04-data` (Erasure).
 *
 * Both steps always run, and a repeat delivery re-cleans an already-erased row (a write that
 * slipped in after the first erase is removed), so a delivery that failed between the steps, or a
 * late write, is repaired by the retry.
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
        // Party first: it takes the row lock and commits erased_at, after which no case can be linked
        // to this party (the link is checked under the same lock). Cases run second, so every link
        // that committed before the party step is cleaned here, and a repeat delivery re-cleans any
        // linked case that is still not anonymised.
        val party = parties.anonymize(partyId, now)
        val anonymisedCases = cases.anonymizeLinkedTo(partyId, now)
        val outcome = when {
            party == PartyAnonymisation.FIRST || anonymisedCases > 0 -> ErasureOutcome.ERASED
            party == PartyAnonymisation.REPEAT -> ErasureOutcome.REPEATED
            else -> ErasureOutcome.UNKNOWN
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
}
