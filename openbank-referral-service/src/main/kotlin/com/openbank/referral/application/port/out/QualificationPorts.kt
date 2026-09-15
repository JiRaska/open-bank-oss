// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.referral.application.port.out

import com.openbank.referral.domain.QualifyingFact
import com.openbank.referral.domain.ReferralInvite
import com.openbank.referral.domain.ReferralReward
import java.util.UUID

/** The stored fact, and whether THIS call inserted it (false = a redelivery or a second account). */
data class RecordedFact(val fact: QualifyingFact, val inserted: Boolean)

/** ADR-0310 D1 — the account-opened facts qualification is decided from. */
interface ReferralQualifyingFactRepository {
    /**
     * Inserts [fact] unless one already exists for its `eventId` or for its (party, event name), and
     * returns what is stored. Insert-if-absent is enforced by the database, not by a read-then-write,
     * so two deliveries racing cannot both insert.
     */
    suspend fun recordIfAbsent(fact: QualifyingFact): RecordedFact

    suspend fun find(partyId: UUID, eventName: String): QualifyingFact?
}

/** Referee-keyed reads the qualification paths need; kept apart from the token-keyed invite port. */
interface ReferralRefereeLookup {
    /** Every ATTRIBUTED invite whose referee is [refereePartyId], with the stored token hash as `token`. */
    suspend fun attributedInvites(refereePartyId: UUID): List<ReferralInvite>

    /** The reward [refereePartyId] already earned under [programId], if any — at most one exists. */
    suspend fun rewardForReferee(refereePartyId: UUID, programId: UUID): ReferralReward?
}
