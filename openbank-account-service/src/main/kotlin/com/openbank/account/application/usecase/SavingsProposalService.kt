// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.ScaChallengeClient
import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.event.SavingsWithdrawalApproved
import com.openbank.account.domain.model.SavingsDelegationIntent
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class ProposalNotFoundException(id: UUID) : RuntimeException("Withdrawal proposal not found: $id")
class ProposalForbiddenException(message: String) : RuntimeException(message)
class ProposalScaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ProposalExpiredException(id: UUID, expiredAt: OffsetDateTime) :
    RuntimeException("Withdrawal proposal $id expired at $expiredAt")

data class ProposeWithdrawalCommand(
    val accountId: UUID,
    val delegatePartyId: UUID,
    val amountMinor: Long,
    val currency: String,
    val note: String?,
)

data class ProposalCreated(val proposal: WithdrawalProposal, val approvalId: String)

/**
 * The propose-only maker-checker flow (ADR-0232 D8 / AC8): a delegate holding
 * SAVINGS_PROPOSE_WITHDRAW creates a proposal; the owner's SCA-bound decision is
 * the only path to APPROVED, which emits SavingsWithdrawalApproved — the executable
 * instruction the payments path consumes. The delegate can never decide
 * (ApprovalStore enforces segregation of duties) and never executes.
 */
@ApplicationScoped
class SavingsProposalService(
    private val accountRepository: AccountRepository,
    private val proposalRepository: WithdrawalProposalRepository,
    private val savingsGuard: SavingsGoalDelegationGuard,
    private val approvalStore: ApprovalStore,
    private val scaChallengeClient: ScaChallengeClient,
    private val clock: Clock,
) {

    suspend fun propose(command: ProposeWithdrawalCommand): ProposalCreated {
        val allowed = savingsGuard.isAuthorized(
            command.accountId,
            command.delegatePartyId,
            SavingsDelegationIntent.PROPOSE_WITHDRAW,
        )
        if (!allowed) {
            throw ProposalForbiddenException(
                "party ${command.delegatePartyId} holds no SAVINGS_PROPOSE_WITHDRAW grant on account ${command.accountId}",
            )
        }
        val now = OffsetDateTime.now(clock)
        val proposal = WithdrawalProposal(
            id = Ids.newId(),
            accountId = command.accountId,
            delegatePartyId = command.delegatePartyId,
            amountMinor = command.amountMinor,
            currency = command.currency,
            note = command.note,
            createdAt = now,
            expiresAt = now.plus(PROPOSAL_TTL),
        )
        val approval = approvalStore.create(
            action = ACTION_EXECUTE,
            resourceId = proposal.id.toString(),
            makerId = command.delegatePartyId.toString(),
        )
        return ProposalCreated(proposalRepository.save(proposal.copy(approvalId = approval.id)), approval.id)
    }

    suspend fun decide(
        accountId: UUID,
        proposalId: UUID,
        decidedByPartyId: UUID,
        approve: Boolean,
        scaSessionId: UUID,
    ): WithdrawalProposal {
        val account = accountRepository.findById(accountId)
            ?: throw ProposalNotFoundException(proposalId)
        if (account.partyId != decidedByPartyId) {
            throw ProposalForbiddenException(
                "only the account owner can decide a withdrawal proposal on account $accountId",
            )
        }
        val proposal = proposalRepository.findById(proposalId)
            ?: throw ProposalNotFoundException(proposalId)
        if (proposal.accountId != accountId) {
            throw ProposalNotFoundException(proposalId)
        }
        // Checked BEFORE the challenge is consumed: a doomed decision must not burn the owner's
        // one-shot second factor. Read off the proposal's own window rather than its stored
        // status, so the answer does not depend on the sweep having already run.
        if (proposal.isExpiredAt(OffsetDateTime.now(clock))) {
            throw ProposalExpiredException(proposalId, proposal.expiresAt)
        }
        verifyOwnerSca(decidedByPartyId, scaSessionId)

        val approvalId = checkNotNull(proposal.approvalId) { "proposal $proposalId has no approval record" }
        approvalStore.decide(approvalId, decidedByPartyId.toString(), approve)
            ?: error("approval $approvalId not found")

        val now = OffsetDateTime.now(clock)
        return if (approve) {
            val approved = proposal.approve(decidedByPartyId, scaSessionId, now)
            proposalRepository.save(
                approved,
                SavingsWithdrawalApproved(
                    aggregateId = approved.id,
                    accountId = approved.accountId,
                    delegatePartyId = approved.delegatePartyId,
                    amountMinor = approved.amountMinor,
                    currency = approved.currency,
                    approvalId = approvalId,
                    scaSessionId = scaSessionId,
                    occurredAt = clock.instant(),
                ),
            )
        } else {
            proposalRepository.save(proposal.reject(decidedByPartyId, now))
        }
    }

    suspend fun cancel(accountId: UUID, proposalId: UUID, delegatePartyId: UUID): WithdrawalProposal {
        val proposal = proposalRepository.findById(proposalId)
            ?: throw ProposalNotFoundException(proposalId)
        if (proposal.accountId != accountId || proposal.delegatePartyId != delegatePartyId) {
            throw ProposalForbiddenException("only the proposing delegate can cancel proposal $proposalId")
        }
        return proposalRepository.save(proposal.cancel(OffsetDateTime.now(clock)))
    }

    suspend fun listForAccount(accountId: UUID, status: WithdrawalProposalStatus?): List<WithdrawalProposal> =
        proposalRepository.findByAccountAndStatus(accountId, status)

    /**
     * Binds the decision to the owner's own challenge, then SPENDS it.
     *
     * Two things were wrong here and each alone made the feature untrue.
     *
     * 1. It asserted `status == "COMPLETED"`. A decoupled challenge (PUSH_NOTIFICATION /
     *    BIOMETRIC) — which is what an owner approving on their phone actually produces — sits at
     *    PENDING while already holding a signature-verified device decision. `verify()` promotes
     *    it, and NOTHING a customer can reach calls verify: customer-edge exposes create / read /
     *    decision only, and `decision` records the signed decision without promoting the
     *    challenge. So this pre-check rejected exactly the challenges the flow depends on, and
     *    every owner approval failed with "is not completed". Identical defect to #3537 in
     *    delegation-service; the fixtures hid it by handing the service a COMPLETED challenge,
     *    a state the customer path never reaches.
     *
     * 2. It only ever READ the challenge. Nothing spent it, so one approved challenge authorised
     *    an unbounded number of proposals: approve a 100 CZK proposal, then reuse the same
     *    scaSessionId to approve every other PENDING proposal on the account. Single-use is the
     *    entire point of a second factor.
     *
     * `consume` fixes both: it resolves a pending decoupled challenge itself, refuses one that was
     * never approved or is already spent (409), checks the party it is TOLD to expect, and
     * enforces dynamic linking. Approval is still enforced — by the component that owns it.
     * Purpose is checked here because consume is not told the purpose.
     */
    private suspend fun verifyOwnerSca(ownerPartyId: UUID, scaSessionId: UUID) {
        val challenge = try {
            scaChallengeClient.getChallenge(scaSessionId)
        } catch (e: NotFoundException) {
            throw ProposalScaException("SCA challenge $scaSessionId not found", e)
        } catch (e: Exception) {
            throw ProposalScaException("SCA challenge $scaSessionId could not be verified", e)
        }
        if (challenge.partyId != ownerPartyId || challenge.purpose != SCA_PURPOSE) {
            throw ProposalScaException("SCA challenge $scaSessionId does not match the owner or purpose")
        }
        @Suppress("TooGenericExceptionCaught") // includes sca-service's 409 for an already-spent challenge
        try {
            scaChallengeClient.consumeChallenge(scaSessionId, ownerPartyId)
        } catch (e: Exception) {
            throw ProposalScaException("SCA challenge $scaSessionId could not be consumed", e)
        }
    }

    /**
     * Marks the closed-window proposals EXPIRED. Idempotent and batched; a proposal the sweep has
     * not reached yet is already un-approvable via [WithdrawalProposal.isExpiredAt], so this is
     * bookkeeping for the owner's inbox, not the security boundary.
     */
    suspend fun expireStale(limit: Int = EXPIRY_BATCH): Int {
        val now = OffsetDateTime.now(clock)
        val stale = proposalRepository.findExpirable(now, limit)
        stale.forEach { proposalRepository.save(it.expire(now)) }
        return stale.size
    }

    private companion object {
        const val ACTION_EXECUTE = "savings.withdraw.execute"
        const val SCA_PURPOSE = "SAVINGS_WITHDRAW_APPROVAL"
        const val EXPIRY_BATCH = 200
        val PROPOSAL_TTL: Duration = Duration.ofDays(7)
    }
}
