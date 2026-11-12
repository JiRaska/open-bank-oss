// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.pid.application.port.`in`.ErasePartyUseCase
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
 * Both steps are idempotent and BOTH always run, so a delivery that failed between them is
 * completed by the retry — the first step's "already erased" must not skip the second.
 * Nothing personal is logged: the party id is the only value written.
 */
@ApplicationScoped
class PartyErasureService(
    private val parties: PartyRepository,
    private val cases: VerificationCaseRepository,
    private val clock: Clock,
) : ErasePartyUseCase {

    private val log = Logger.getLogger(PartyErasureService::class.java)

    override suspend fun erase(partyId: UUID): Boolean {
        val now = clock.instant()
        val anonymisedCases = cases.anonymizeLinkedTo(partyId, now)
        val anonymisedParty = parties.anonymize(partyId, now)
        val erased = anonymisedParty || anonymisedCases > 0
        if (erased) {
            log.infof(
                "GDPR Art. 17: erased identity data of party %s (party row: %s, verification cases: %d)",
                partyId,
                anonymisedParty,
                anonymisedCases,
            )
        } else {
            log.debugf("GDPR Art. 17: party %s already erased or unknown to pid-service, nothing to do", partyId)
        }
        return erased
    }
}
