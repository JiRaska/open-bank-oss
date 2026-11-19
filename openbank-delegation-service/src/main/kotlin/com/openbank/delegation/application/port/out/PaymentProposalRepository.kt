// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.port.out

import com.openbank.delegation.domain.model.PaymentProposal
import java.time.OffsetDateTime
import java.util.UUID

/** What [PaymentProposalRepository.create] did. */
sealed interface ProposalCreateOutcome {
    data class Created(val proposal: PaymentProposal) : ProposalCreateOutcome

    /** An identical open proposal already exists (ADR-0383 D6 dedupe); nothing was written. */
    data class Deduplicated(val proposal: PaymentProposal) : ProposalCreateOutcome

    data class LimitReached(val reason: String) : ProposalCreateOutcome
}

data class ProposalLimits(val maxOpenPerParty: Int, val maxCreatedPerPartyPerDay: Int)

/**
 * Every write commits its lifecycle outbox event in the same transaction. Every transition is a
 * compare-and-set under a row lock: two concurrent approvals of one proposal produce one APPROVED.
 */
interface PaymentProposalRepository {
    /**
     * Under a per-party transaction lock: expire this party's stale PENDING rows, return an
     * identical open proposal if one exists, enforce [limits], otherwise insert [candidate].
     * [candidate] is built by the caller so the id is inside its payload hash before the row exists.
     */
    suspend fun create(candidate: PaymentProposal, limits: ProposalLimits, now: OffsetDateTime): ProposalCreateOutcome

    suspend fun findById(id: UUID): PaymentProposal?

    suspend fun findByParty(partyId: UUID, limit: Int): List<PaymentProposal>

    /** Lock the row, apply [transition], persist + outbox. Null when the row does not exist. */
    suspend fun transition(id: UUID, transition: (PaymentProposal) -> PaymentProposal): PaymentProposal?

    /**
     * REJECT every PENDING proposal made under [grantId], with events, inside the CALLER's open
     * transaction (the grant's revoke/suspend write), so a grant can never leave ACTIVE while a
     * proposal made under it stays approvable.
     */
    fun rejectPendingForGrant(grantId: UUID, now: OffsetDateTime): io.smallrye.mutiny.Uni<Void>
}

/** A deduplication probe shared by the repository and its tests. */
/** Scoped to the same client and grant: another client's identical proposal is its own record. */
fun List<PaymentProposal>.openDuplicateOf(candidate: PaymentProposal, now: OffsetDateTime): PaymentProposal? =
    firstOrNull {
        it.isOpen(now) &&
            it.mcpClientId == candidate.mcpClientId &&
            it.grantId == candidate.grantId &&
            it.payload.sameInstructionAs(candidate.payload)
    }
