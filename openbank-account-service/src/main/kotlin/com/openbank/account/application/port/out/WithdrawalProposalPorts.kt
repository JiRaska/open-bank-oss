// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.domain.event.DomainEvent
import java.time.OffsetDateTime
import java.util.UUID

interface WithdrawalProposalRepository {
    suspend fun save(proposal: WithdrawalProposal): WithdrawalProposal
    suspend fun save(proposal: WithdrawalProposal, event: DomainEvent): WithdrawalProposal
    suspend fun findById(id: UUID): WithdrawalProposal?
    suspend fun findByAccountAndStatus(accountId: UUID, status: WithdrawalProposalStatus?): List<WithdrawalProposal>

    /** PENDING proposals whose window has closed, oldest first — the expiry sweep's input. */
    suspend fun findExpirable(now: OffsetDateTime, limit: Int): List<WithdrawalProposal>
}

data class ScaChallengeSnapshot(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

interface ScaChallengeClient {
    suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot

    /**
     * Spends the challenge. sca-service resolves a pending decoupled challenge itself, refuses one
     * that was never approved or is already spent, and enforces dynamic linking — so this is the
     * component that owns "was this really approved", not the caller. See [ScaChallengeClient]
     * usage in `SavingsProposalService` for why a caller-side completeness pre-check is wrong.
     */
    suspend fun consumeChallenge(challengeId: UUID, expectedPartyId: UUID): ScaChallengeSnapshot
}
