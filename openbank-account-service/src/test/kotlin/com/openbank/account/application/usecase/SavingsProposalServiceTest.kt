// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.ScaChallengeClient
import com.openbank.account.application.port.out.ScaChallengeSnapshot
import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SavingsProposalServiceTest {

    private val accountRepository: AccountRepository = mockk()
    private val proposalRepository: WithdrawalProposalRepository = mockk()
    private val savingsGuard: SavingsGoalDelegationGuard = mockk()
    private val approvalStore: ApprovalStore = mockk()
    private val scaClient: ScaChallengeClient = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: SavingsProposalService

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service =
            SavingsProposalService(accountRepository, proposalRepository, savingsGuard, approvalStore, scaClient, clock)
    }

    private fun command() = ProposeWithdrawalCommand(
        accountId = accountId,
        delegatePartyId = delegate,
        amountMinor = 150_000,
        currency = "CZK",
        note = "kolo",
    )

    private fun pendingApproval(maker: UUID) = PendingApproval(
        id = "approval-1",
        action = "savings.withdraw.execute",
        resourceId = null,
        makerId = maker.toString(),
        status = ApprovalStatus.PENDING,
        createdAt = now,
    )

    @Test
    fun `propose without the grant is forbidden`(): Unit = runBlocking {
        coEvery { savingsGuard.isAuthorized(accountId, delegate, any()) } returns false
        assertThatThrownBy { runBlocking { service.propose(command()) } }
            .isInstanceOf(ProposalForbiddenException::class.java)
        coVerify(exactly = 0) { approvalStore.create(any(), any(), any(), any()) }
    }

    @Test
    fun `propose creates a PENDING proposal and an approval record`(): Unit = runBlocking {
        coEvery { savingsGuard.isAuthorized(accountId, delegate, any()) } returns true
        coEvery { approvalStore.create(any(), any(), any(), any()) } returns pendingApproval(delegate)
        coEvery { proposalRepository.save(any<WithdrawalProposal>()) } answers { firstArg() }

        val created = service.propose(command())

        assertThat(created.approvalId).isEqualTo("approval-1")
        assertThat(created.proposal.status).isEqualTo(WithdrawalProposalStatus.PENDING)
        assertThat(created.proposal.approvalId).isEqualTo("approval-1")
        coVerify {
            approvalStore.create("savings.withdraw.execute", created.proposal.id.toString(), delegate.toString())
        }
    }

    @Test
    fun `decide by a non-owner is forbidden`(): Unit = runBlocking {
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account

        assertThatThrownBy {
            runBlocking { service.decide(accountId, UUID.randomUUID(), UUID.randomUUID(), true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalForbiddenException::class.java)
    }

    @Test
    fun `approve flips the proposal and emits the executable event`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { approvalStore.decide("approval-1", owner.toString(), true) } returns
            pendingApproval(delegate).copy(status = ApprovalStatus.APPROVED)
        coEvery { proposalRepository.save(any<WithdrawalProposal>(), any()) } answers { firstArg() }

        val decided = service.decide(accountId, proposal.id, owner, true, UUID.randomUUID())

        assertThat(decided.status).isEqualTo(WithdrawalProposalStatus.APPROVED)
        coVerify { proposalRepository.save(any<WithdrawalProposal>(), any()) }
    }

    @Test
    fun `delegate deciding a proposal is stopped by the owner check`(): Unit = runBlocking {
        val proposal = proposal()
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, delegate, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalForbiddenException::class.java)
        coVerify(exactly = 0) { approvalStore.decide(any(), any(), any()) }
    }

    @Test
    fun `a store-level decision failure propagates instead of being swallowed`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { approvalStore.decide("approval-1", owner.toString(), true) } throws
            SelfApprovalNotAllowedException(owner.toString())

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `reject flips to REJECTED without emitting an event`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { approvalStore.decide("approval-1", owner.toString(), false) } returns
            pendingApproval(delegate).copy(status = ApprovalStatus.REJECTED)
        coEvery { proposalRepository.save(any<WithdrawalProposal>()) } answers { firstArg() }

        val decided = service.decide(accountId, proposal.id, owner, false, UUID.randomUUID())

        assertThat(decided.status).isEqualTo(WithdrawalProposalStatus.REJECTED)
        coVerify(exactly = 0) { proposalRepository.save(any<WithdrawalProposal>(), any()) }
    }

    @Test
    fun `wrong SCA purpose blocks the decision`(): Unit = runBlocking {
        val proposal = proposal()
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account
        coEvery { proposalRepository.findById(proposal.id) } returns proposal
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = owner,
            purpose = "CONSENT_GRANT",
            status = "COMPLETED",
        )

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalScaException::class.java)
    }

    private fun stubOwnerAndProposal(proposal: WithdrawalProposal) {
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account
        coEvery { proposalRepository.findById(proposal.id) } returns proposal
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = owner,
            purpose = "SAVINGS_WITHDRAW_APPROVAL",
            status = "COMPLETED",
        )
    }

    private fun proposal() = WithdrawalProposal(
        id = UUID.randomUUID(),
        accountId = accountId,
        delegatePartyId = delegate,
        amountMinor = 150_000,
        currency = "CZK",
        approvalId = "approval-1",
        createdAt = now,
    )
}
