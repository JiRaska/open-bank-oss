// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.ApprovalGroupRevisionRepository
import com.openbank.account.application.port.out.PartyMandateProjectionRepository
import com.openbank.account.application.port.out.ScaChallengeClient
import com.openbank.account.application.port.out.ScaChallengeSnapshot
import com.openbank.account.application.port.out.WithdrawalDecisionResult
import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.model.Account
import com.openbank.account.domain.model.ApprovalGroupRevision
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.account.domain.model.PartyMandateProjection
import com.openbank.account.domain.model.SavingsWithdrawalScaReference
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.domain.identifiers.Ids
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
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
    private val partyMandateRepository: PartyMandateProjectionRepository = mockk()
    private val approvalGroupRepository: ApprovalGroupRevisionRepository = mockk()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var service: SavingsProposalService

    private val accountId: UUID = UUID.randomUUID()
    private val owner: UUID = UUID.randomUUID()
    private val delegate: UUID = UUID.randomUUID()
    private val now: OffsetDateTime = OffsetDateTime.now(clock)

    @BeforeEach
    fun setUp() {
        service =
            SavingsProposalService(
                accountRepository,
                proposalRepository,
                savingsGuard,
                approvalStore,
                scaClient,
                partyMandateRepository,
                approvalGroupRepository,
                clock,
            )
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
        coEvery { savingsGuard.authorization(accountId, delegate, any()) } returns null
        assertThatThrownBy { runBlocking { service.propose(command()) } }
            .isInstanceOf(ProposalForbiddenException::class.java)
        coVerify(exactly = 0) { approvalStore.create(any(), any(), any(), any()) }
    }

    @Test
    fun `propose creates a PENDING proposal and an approval record`(): Unit = runBlocking {
        coEvery { savingsGuard.authorization(accountId, delegate, any()) } returns
            SavingsGoalDelegationGuard.Authorization(owner, null)
        coEvery { proposalRepository.findByAccountAndStatus(accountId, WithdrawalProposalStatus.PENDING) } returns
            emptyList()
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
    fun `propose replays the still-pending identical proposal instead of stacking a duplicate (ADR-0295)`(): Unit =
        runBlocking {
            val original = WithdrawalProposal(
                id = Ids.newId(),
                accountId = accountId,
                delegatePartyId = delegate,
                amountMinor = 150_000,
                currency = "CZK",
                note = "kolo",
                approvalId = "approval-orig",
                createdAt = now.minusSeconds(60),
                expiresAt = now.plus(Duration.ofHours(47)),
            )
            coEvery { savingsGuard.authorization(accountId, delegate, any()) } returns
                SavingsGoalDelegationGuard.Authorization(owner, null)
            coEvery { proposalRepository.findByAccountAndStatus(accountId, WithdrawalProposalStatus.PENDING) } returns
                listOf(original)

            val created = service.propose(command())

            assertThat(created.proposal).isEqualTo(original)
            assertThat(created.approvalId).isEqualTo("approval-orig")
            // No second approval record, no second row.
            coVerify(exactly = 0) { approvalStore.create(any(), any(), any(), any()) }
            coVerify(exactly = 0) { proposalRepository.save(any<WithdrawalProposal>()) }
        }

    @Test
    fun `propose persists a fresh proposal when the identical one already expired`(): Unit = runBlocking {
        val expired = WithdrawalProposal(
            id = Ids.newId(),
            accountId = accountId,
            delegatePartyId = delegate,
            amountMinor = 150_000,
            currency = "CZK",
            note = "kolo",
            approvalId = "approval-old",
            createdAt = now.minus(Duration.ofDays(3)),
            expiresAt = now.minus(Duration.ofDays(1)),
        )
        coEvery { savingsGuard.authorization(accountId, delegate, any()) } returns
            SavingsGoalDelegationGuard.Authorization(owner, null)
        // The sweep may not have flipped it yet — it is still PENDING but expired.
        coEvery { proposalRepository.findByAccountAndStatus(accountId, WithdrawalProposalStatus.PENDING) } returns
            listOf(expired)
        coEvery { approvalStore.create(any(), any(), any(), any()) } returns pendingApproval(delegate)
        coEvery { proposalRepository.save(any<WithdrawalProposal>()) } answers { firstArg() }

        val created = service.propose(command())

        assertThat(created.proposal.id).isNotEqualTo(expired.id)
        coVerify(exactly = 1) { proposalRepository.save(any<WithdrawalProposal>()) }
    }

    @Test
    fun `N of M proposal snapshots the exact active group revision and excludes the maker`(): Unit = runBlocking {
        val groupId = UUID.randomUUID()
        val approvers = setOf(delegate, UUID.randomUUID(), UUID.randomUUID())
        val grant = DelegatedAccessGrant(
            id = UUID.randomUUID(),
            accountId = accountId,
            grantorPartyId = owner,
            granteePartyId = delegate,
            capabilities = setOf(DelegatedAccessGrant.CAP_SAVINGS_PROPOSE_WITHDRAW),
            approvalPolicy = "N_OF_M",
            requiredApprovals = 2,
            approvalGroupId = groupId,
            approvalGroupRevision = 4,
            validFrom = now.minusDays(1),
        )
        coEvery { savingsGuard.authorization(accountId, delegate, any()) } returns
            SavingsGoalDelegationGuard.Authorization(owner, grant)
        coEvery { approvalGroupRepository.findLatest(groupId) } returns ApprovalGroupRevision(
            groupId = groupId,
            ownerPartyId = owner,
            revision = 4,
            name = "Treasury",
            members = approvers,
            threshold = 2,
            active = true,
        )
        coEvery { proposalRepository.findByAccountAndStatus(accountId, WithdrawalProposalStatus.PENDING) } returns
            emptyList()
        coEvery { approvalStore.create(any(), any(), any(), any()) } returns pendingApproval(delegate)
        coEvery { proposalRepository.save(any<WithdrawalProposal>()) } answers { firstArg() }

        val proposal = service.propose(command()).proposal

        assertThat(proposal.delegationGrantId).isEqualTo(grant.id)
        assertThat(proposal.approvalGroupId).isEqualTo(groupId)
        assertThat(proposal.approvalGroupRevision).isEqualTo(4)
        assertThat(proposal.requiredApprovals).isEqualTo(2)
        assertThat(proposal.eligibleApproverIds).containsExactlyInAnyOrderElementsOf(approvers - delegate)
    }

    @Test
    fun `decide by a non-owner is forbidden`(): Unit = runBlocking {
        val account = mockk<Account>()
        val proposal = proposal()
        val stranger = UUID.randomUUID()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account
        coEvery { proposalRepository.findById(proposal.id) } returns proposal
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            UUID.randomUUID(),
            stranger,
            "SAVINGS_WITHDRAW_APPROVAL",
            "PENDING",
            amount = "1500.00",
            currency = "CZK",
            reference = SavingsWithdrawalScaReference.of(proposal.id, approve = true),
        )

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, stranger, true, UUID.randomUUID()) }
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
        coVerify { proposalRepository.recordDecision(proposal.id, owner, true, any(), any(), any()) }
    }

    @Test
    fun `entity owner accepts the human actor authenticated by SCA under an exact sole mandate`(): Unit = runBlocking {
        val proposal = proposal()
        val representative = UUID.randomUUID()
        stubOwnerAndProposal(proposal, scaActor = representative)
        coEvery { partyMandateRepository.findActive(owner, representative) } returns listOf(
            PartyMandateProjection(UUID.randomUUID(), owner, representative, "SOLE", 1, true),
        )
        coEvery { approvalStore.decide("approval-1", representative.toString(), true) } returns
            pendingApproval(delegate).copy(status = ApprovalStatus.APPROVED)
        coEvery { proposalRepository.save(any<WithdrawalProposal>(), any()) } answers { firstArg() }

        val decided = service.decide(accountId, proposal.id, owner, true, UUID.randomUUID())

        assertThat(decided.decidedBy).isEqualTo(representative)
        coVerify { scaClient.consumeChallenge(any(), representative, any(), any(), any()) }
        coVerify { proposalRepository.recordDecision(proposal.id, representative, true, any(), any(), any()) }
    }

    @Test
    fun `joint mandate cannot enter the single-decision path and does not burn SCA`(): Unit = runBlocking {
        val proposal = proposal()
        val representative = UUID.randomUUID()
        stubOwnerAndProposal(proposal, scaActor = representative)
        coEvery { partyMandateRepository.findActive(owner, representative) } returns listOf(
            PartyMandateProjection(UUID.randomUUID(), owner, representative, "JOINT", 2, true),
        )

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalForbiddenException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { approvalStore.decide(any(), any(), any()) }
    }

    @Test
    fun `delegate deciding a proposal is stopped by the owner check`(): Unit = runBlocking {
        val proposal = proposal()
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account
        coEvery { proposalRepository.findById(proposal.id) } returns proposal
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            UUID.randomUUID(),
            delegate,
            "SAVINGS_WITHDRAW_APPROVAL",
            "PENDING",
            amount = "1500.00",
            currency = "CZK",
            reference = SavingsWithdrawalScaReference.of(proposal.id, approve = true),
        )

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, delegate, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalForbiddenException::class.java)
        coVerify(exactly = 0) { approvalStore.decide(any(), any(), any()) }
    }

    @Test
    fun `a repository decision failure propagates instead of being swallowed`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { proposalRepository.recordDecision(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("concurrent decision conflict")

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `reject flips to REJECTED without emitting an event`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal, approve = false)
        coEvery { approvalStore.decide("approval-1", owner.toString(), false) } returns
            pendingApproval(delegate).copy(status = ApprovalStatus.REJECTED)
        val decided = service.decide(accountId, proposal.id, owner, false, UUID.randomUUID())

        assertThat(decided.status).isEqualTo(WithdrawalProposalStatus.REJECTED)
        coVerify { proposalRepository.recordDecision(proposal.id, owner, false, any(), any(), any()) }
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

    @Test
    fun `SCA signed for the opposite decision is rejected without being spent`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = owner,
            purpose = "SAVINGS_WITHDRAW_APPROVAL",
            status = "PENDING",
            amount = "1500.00",
            currency = "CZK",
            reference = SavingsWithdrawalScaReference.of(proposal.id, approve = false),
        )

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalScaException::class.java)

        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { proposalRepository.recordDecision(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `retry recovers an exactly bound challenge already consumed before the account commit`(): Unit = runBlocking {
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = owner,
            purpose = "SAVINGS_WITHDRAW_APPROVAL",
            status = "COMPLETED",
            amount = "1500.0",
            currency = "czk",
            reference = SavingsWithdrawalScaReference.of(proposal.id, approve = true),
            consumedAt = now.minusSeconds(1),
        )

        val decided = service.decide(accountId, proposal.id, owner, true, UUID.randomUUID())

        assertThat(decided.status).isEqualTo(WithdrawalProposalStatus.APPROVED)
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { proposalRepository.recordDecision(any(), owner, true, any(), any(), any()) }
    }

    @Test
    fun `approve accepts a PENDING decoupled challenge and lets consume promote it`(): Unit = runBlocking {
        // The state every customer-driven approval is actually in. The previous
        // `status == "COMPLETED"` pre-check rejected exactly this, so no owner could ever approve
        // a proposal — and no test noticed, because every fixture handed the service a COMPLETED
        // challenge, a state customer-edge cannot produce. Same defect as #3537 in
        // delegation-service.
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { approvalStore.decide("approval-1", owner.toString(), true) } returns
            pendingApproval(delegate).copy(status = ApprovalStatus.APPROVED)
        coEvery { proposalRepository.save(any<WithdrawalProposal>(), any()) } answers { firstArg() }

        val decided = service.decide(accountId, proposal.id, owner, true, UUID.randomUUID())

        assertThat(decided.status).isEqualTo(WithdrawalProposalStatus.APPROVED)
        // Approval is still enforced — by consume, which owns it.
        coVerify(exactly = 1) { scaClient.consumeChallenge(any(), owner, "1500.00", "CZK", any()) }
    }

    @Test
    fun `the challenge is SPENT, not merely read`(): Unit = runBlocking {
        // Without consume, one approved challenge authorised every PENDING proposal on the
        // account: single-use is the whole point of a second factor.
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { approvalStore.decide("approval-1", owner.toString(), true) } returns
            pendingApproval(delegate).copy(status = ApprovalStatus.APPROVED)
        coEvery { proposalRepository.save(any<WithdrawalProposal>(), any()) } answers { firstArg() }

        service.decide(accountId, proposal.id, owner, true, UUID.randomUUID())

        coVerify(exactly = 1) { scaClient.consumeChallenge(any(), owner, "1500.00", "CZK", any()) }
    }

    @Test
    fun `a refused consume blocks the decision`(): Unit = runBlocking {
        // sca-service answers 409 for an already-spent challenge and refuses one never approved.
        val proposal = proposal()
        stubOwnerAndProposal(proposal)
        coEvery { scaClient.consumeChallenge(any(), owner, any(), any(), any()) } throws
            IllegalStateException("already consumed")

        assertThatThrownBy {
            runBlocking { service.decide(accountId, proposal.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalScaException::class.java)
        coVerify(exactly = 0) { approvalStore.decide(any(), any(), any()) }
    }

    @Test
    fun `an expired proposal cannot be approved and does not burn the challenge`(): Unit = runBlocking {
        val stale = proposal().copy(createdAt = now.minusDays(30), expiresAt = now.minusDays(23))
        stubOwnerAndProposal(stale)

        assertThatThrownBy {
            runBlocking { service.decide(accountId, stale.id, owner, true, UUID.randomUUID()) }
        }.isInstanceOf(ProposalExpiredException::class.java)

        // Checked before the SCA leg: a doomed decision must not spend the owner's one-shot factor.
        coVerify(exactly = 0) { scaClient.consumeChallenge(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { approvalStore.decide(any(), any(), any()) }
    }

    @Test
    fun `expiry is decided by the window, not by the sweep having run`(): Unit = runBlocking {
        // The stored status is still PENDING — the sweep has not reached it. Approval must still
        // refuse, or the gap between the window closing and the sweep running is an open door.
        val stale = proposal().copy(createdAt = now.minusDays(30), expiresAt = now.minusSeconds(1))
        assertThat(stale.status).isEqualTo(WithdrawalProposalStatus.PENDING)
        assertThat(stale.isExpiredAt(now)).isTrue()
    }

    @Test
    fun `the sweep expires only closed-window proposals`(): Unit = runBlocking {
        val stale = proposal().copy(createdAt = now.minusDays(30), expiresAt = now.minusDays(23))
        coEvery { proposalRepository.findExpirable(any(), any()) } returns listOf(stale)
        coEvery { proposalRepository.save(any<WithdrawalProposal>()) } answers { firstArg() }

        val count = service.expireStale()

        assertThat(count).isEqualTo(1)
        coVerify(exactly = 1) {
            proposalRepository.save(match<WithdrawalProposal> { it.status == WithdrawalProposalStatus.EXPIRED })
        }
    }

    private fun stubOwnerAndProposal(proposal: WithdrawalProposal, scaActor: UUID = owner, approve: Boolean = true) {
        val account = mockk<Account>()
        io.mockk.every { account.partyId } returns owner
        coEvery { accountRepository.findById(accountId) } returns account
        coEvery { proposalRepository.findById(proposal.id) } returns proposal
        // PENDING, not COMPLETED: the state a decoupled challenge is actually in when the owner
        // approves from their phone. Nothing a customer can reach calls sca-service's verify().
        coEvery { scaClient.getChallenge(any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = scaActor,
            purpose = "SAVINGS_WITHDRAW_APPROVAL",
            status = "PENDING",
            amount = "1500.00",
            currency = "CZK",
            reference = SavingsWithdrawalScaReference.of(proposal.id, approve),
        )
        coEvery { scaClient.consumeChallenge(any(), scaActor, any(), any(), any()) } returns ScaChallengeSnapshot(
            id = UUID.randomUUID(),
            partyId = scaActor,
            purpose = "SAVINGS_WITHDRAW_APPROVAL",
            status = "COMPLETED",
        )
        coEvery {
            proposalRepository.recordDecision(proposal.id, scaActor, any(), any(), any(), any())
        } answers {
            val approved = thirdArg<Boolean>()
            val scaSessionId = arg<UUID>(3)
            val decidedAt = arg<OffsetDateTime>(4)
            WithdrawalDecisionResult(
                proposal = if (approved) {
                    proposal.approve(scaActor, scaSessionId, decidedAt)
                } else {
                    proposal.reject(scaActor, decidedAt)
                },
                acceptedApprovals = if (approved) 1 else 0,
                replayed = false,
            )
        }
    }

    private fun proposal() = WithdrawalProposal(
        id = UUID.randomUUID(),
        accountId = accountId,
        delegatePartyId = delegate,
        amountMinor = 150_000,
        currency = "CZK",
        approvalId = "approval-1",
        createdAt = now,
        expiresAt = now.plusDays(7),
    )
}
