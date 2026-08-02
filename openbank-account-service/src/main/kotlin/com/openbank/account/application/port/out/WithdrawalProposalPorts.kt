// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.port.out

import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.domain.event.DomainEvent
import java.util.UUID

interface WithdrawalProposalRepository {
    suspend fun save(proposal: WithdrawalProposal): WithdrawalProposal
    suspend fun save(proposal: WithdrawalProposal, event: DomainEvent): WithdrawalProposal
    suspend fun findById(id: UUID): WithdrawalProposal?
    suspend fun findByAccountAndStatus(accountId: UUID, status: WithdrawalProposalStatus?): List<WithdrawalProposal>
}

data class ScaChallengeSnapshot(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

interface ScaChallengeClient {
    suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot
}
