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
import java.time.OffsetDateTime
import java.util.UUID

class ProposalNotFoundException(id: UUID) : RuntimeException("Withdrawal proposal not found: $id")
class ProposalForbiddenException(message: String) : RuntimeException(message)
class ProposalScaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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
        if (challenge.status != "COMPLETED") {
            throw ProposalScaException("SCA challenge $scaSessionId is not completed")
        }
    }

    private companion object {
        const val ACTION_EXECUTE = "savings.withdraw.execute"
        const val SCA_PURPOSE = "SAVINGS_WITHDRAW_APPROVAL"
    }
}
