// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.AccountRepository
import com.openbank.account.application.port.out.ApprovalGroupRevisionRepository
import com.openbank.account.application.port.out.PartyMandateProjectionRepository
import com.openbank.account.application.port.out.ScaChallengeClient
import com.openbank.account.application.port.out.ScaChallengeSnapshot
import com.openbank.account.application.port.out.WithdrawalProposalRepository
import com.openbank.account.domain.event.SavingsWithdrawalApproved
import com.openbank.account.domain.model.DelegatedAccessGrant
import com.openbank.account.domain.model.SavingsDelegationIntent
import com.openbank.account.domain.model.SavingsWithdrawalScaReference
import com.openbank.account.domain.model.WithdrawalProposal
import com.openbank.account.domain.model.WithdrawalProposalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import java.math.BigDecimal
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

enum class ProposalActorDecision { APPROVED, REJECTED }

data class WithdrawalProposalView(
    val proposal: WithdrawalProposal,
    val approvalsReceived: Int,
    val myDecision: ProposalActorDecision?,
    val canDecide: Boolean,
) {
    val status: WithdrawalProposalStatus get() = proposal.status
    val decidedBy: UUID? get() = proposal.decidedBy
}

private data class ApprovalSnapshot(
    val groupId: UUID?,
    val revision: Long?,
    val threshold: Int,
    val members: Set<UUID>,
)

private const val APPROVAL_POLICY_N_OF_M = "N_OF_M"

private fun requireViableApprovalRoster(groupId: UUID, threshold: Int, members: Set<UUID>) {
    if (members.size < threshold) {
        throw ProposalForbiddenException(
            "approval group $groupId cannot meet its threshold without maker self-approval",
        )
    }
}

private fun requireProposalAccount(proposal: WithdrawalProposal, accountId: UUID) {
    if (proposal.accountId != accountId) throw ProposalNotFoundException(proposal.id)
}

private fun isDynamicallyLinked(
    challenge: ScaChallengeSnapshot,
    proposal: WithdrawalProposal,
    approve: Boolean,
    amount: String,
): Boolean = challenge.reference == SavingsWithdrawalScaReference.of(proposal.id, approve) &&
    challenge.currency?.uppercase() == proposal.currency.uppercase() &&
    challenge.amount?.let { runCatching { BigDecimal(it).compareTo(BigDecimal(amount)) == 0 }.getOrDefault(false) } ==
    true

private data class DecisionContext(val ownerPartyId: UUID, val proposal: WithdrawalProposal)

private suspend fun loadDecisionContext(
    accounts: AccountRepository,
    proposals: WithdrawalProposalRepository,
    accountId: UUID,
    proposalId: UUID,
): DecisionContext {
    val owner = accounts.findById(accountId)?.partyId ?: throw ProposalNotFoundException(proposalId)
    val proposal = proposals.findById(proposalId) ?: throw ProposalNotFoundException(proposalId)
    requireProposalAccount(proposal, accountId)
    return DecisionContext(owner, proposal)
}

private suspend fun authorizeDecisionActor(
    mandates: PartyMandateProjectionRepository,
    owner: UUID,
    proposal: WithdrawalProposal,
    claimed: UUID,
    actor: UUID,
) {
    val denial = when {
        actor != claimed ->
            "the SCA-authenticated actor does not match the caller identity"
        proposal.approvalGroupId != null && actor !in proposal.eligibleApproverIds ->
            "the SCA-authenticated actor is not in the immutable approval roster"
        proposal.approvalGroupId == null &&
            actor != owner &&
            mandates.findActive(owner, actor).none { it.permitsSoleDecision() } ->
            "the SCA-authenticated actor holds no exact SOLE mandate"
        else -> null
    }
    if (denial != null) throw ProposalForbiddenException(denial)
}

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
    private val partyMandateRepository: PartyMandateProjectionRepository,
    private val approvalGroupRepository: ApprovalGroupRevisionRepository,
    private val clock: Clock,
) {

    suspend fun propose(command: ProposeWithdrawalCommand): ProposalCreated {
        val authorization = savingsGuard.authorization(
            command.accountId,
            command.delegatePartyId,
            SavingsDelegationIntent.PROPOSE_WITHDRAW,
        )
        if (authorization == null) {
            throw ProposalForbiddenException(
                "party ${command.delegatePartyId} holds no SAVINGS_PROPOSE_WITHDRAW grant on account ${command.accountId}",
            )
        }
        val approvalSnapshot = approvalSnapshot(authorization, command.delegatePartyId)
        val now = OffsetDateTime.now(clock)
        // Idempotent replay (ADR-0295, #8351): a retried propose with the same natural key —
        // (account, delegate, amount, currency, note) — while the original is still PENDING and
        // unexpired replays the ORIGINAL proposal (and its approval id) instead of stacking a
        // duplicate the owner could approve twice. The check runs AFTER the authorization guard on
        // purpose: a caller with no grant must still get 403, never a replayed proposal. A
        // genuinely intended second identical proposal is still possible — once the first leaves
        // PENDING (decided or expired), the key no longer matches and a new row persists. No DB
        // backstop (see the ADR): a lost true-concurrency race stacks two PENDING proposals, but
        // each still needs its own owner SCA decision, so nothing executes silently.
        proposalRepository.findByAccountAndStatus(command.accountId, WithdrawalProposalStatus.PENDING)
            .firstOrNull { existing ->
                existing.delegatePartyId == command.delegatePartyId &&
                    existing.amountMinor == command.amountMinor &&
                    existing.currency == command.currency &&
                    existing.note == command.note &&
                    existing.approvalId != null &&
                    !existing.isExpiredAt(now)
            }?.let { return ProposalCreated(it, it.approvalId!!) }
        val proposal = WithdrawalProposal(
            id = Ids.newId(),
            accountId = command.accountId,
            delegatePartyId = command.delegatePartyId,
            amountMinor = command.amountMinor,
            currency = command.currency,
            note = command.note,
            delegationGrantId = authorization.grant?.id,
            approvalGroupId = approvalSnapshot.groupId,
            approvalGroupRevision = approvalSnapshot.revision,
            requiredApprovals = approvalSnapshot.threshold,
            eligibleApproverIds = approvalSnapshot.members,
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

    private suspend fun approvalSnapshot(
        authorization: SavingsGoalDelegationGuard.Authorization,
        makerPartyId: UUID,
    ): ApprovalSnapshot {
        val grant = authorization.grant ?: return ApprovalSnapshot(
            groupId = null,
            revision = null,
            threshold = 1,
            members = setOf(authorization.ownerPartyId),
        )
        if (grant.approvalPolicy == DelegatedAccessGrant.APPROVAL_POLICY_SOLO) {
            return ApprovalSnapshot(null, null, 1, setOf(authorization.ownerPartyId))
        }
        require(grant.approvalPolicy == APPROVAL_POLICY_N_OF_M) {
            "unsupported approval policy ${grant.approvalPolicy}"
        }
        val groupId = checkNotNull(grant.approvalGroupId) { "N_OF_M grant ${grant.id} has no approval group" }
        val revision = checkNotNull(grant.approvalGroupRevision) { "N_OF_M grant ${grant.id} has no group revision" }
        val threshold = checkNotNull(grant.requiredApprovals) { "N_OF_M grant ${grant.id} has no threshold" }
        val group = requireCurrentApprovalGroup(groupId, revision, threshold)
        val eligibleMembers = group.members - makerPartyId
        requireViableApprovalRoster(groupId, group.threshold, eligibleMembers)
        return ApprovalSnapshot(groupId, revision, group.threshold, eligibleMembers)
    }

    private suspend fun requireCurrentApprovalGroup(groupId: UUID, revision: Long, threshold: Int) = (
        approvalGroupRepository.findLatest(groupId)
            ?: throw ProposalForbiddenException("approval group $groupId is unavailable")
        ).also { group ->
        if (!group.active || group.revision != revision || group.threshold != threshold) {
            throw ProposalForbiddenException("approval group $groupId changed; the grant must be reissued")
        }
    }

    @Suppress("ThrowsCount") // preserves account/proposal absence, cross-account and expiry semantics
    suspend fun decide(
        accountId: UUID,
        proposalId: UUID,
        callerPartyId: UUID,
        approve: Boolean,
        scaSessionId: UUID,
    ): WithdrawalProposalView {
        val context = loadDecisionContext(accountRepository, proposalRepository, accountId, proposalId)
        val proposal = context.proposal
        // Checked BEFORE the challenge is consumed: a doomed decision must not burn the owner's
        // one-shot second factor. Read off the proposal's own window rather than its stored
        // status, so the answer does not depend on the sweep having already run.
        if (proposal.isExpiredAt(OffsetDateTime.now(clock))) {
            throw ProposalExpiredException(proposalId, proposal.expiresAt)
        }
        val actorPartyId = verifyDecisionSca(context.ownerPartyId, proposal, callerPartyId, approve, scaSessionId)

        val approvalId = checkNotNull(proposal.approvalId) { "proposal $proposalId has no approval record" }
        val now = OffsetDateTime.now(clock)
        val result = proposalRepository.recordDecision(
            proposalId = proposalId,
            actorPartyId = actorPartyId,
            approved = approve,
            scaSessionId = scaSessionId,
            decidedAt = now,
            approvedEvent = SavingsWithdrawalApproved(
                aggregateId = proposal.id,
                accountId = proposal.accountId,
                delegatePartyId = proposal.delegatePartyId,
                amountMinor = proposal.amountMinor,
                currency = proposal.currency,
                approvalId = approvalId,
                scaSessionId = scaSessionId,
                occurredAt = clock.instant(),
                sourceService = "account-service",
            ),
        )
        return WithdrawalProposalView(
            proposal = result.proposal,
            approvalsReceived = result.acceptedApprovals,
            myDecision = if (approve) ProposalActorDecision.APPROVED else ProposalActorDecision.REJECTED,
            canDecide = false,
        )
    }

    suspend fun cancel(accountId: UUID, proposalId: UUID, delegatePartyId: UUID): WithdrawalProposalView {
        val proposal = proposalRepository.findById(proposalId)
            ?: throw ProposalNotFoundException(proposalId)
        if (proposal.accountId != accountId || proposal.delegatePartyId != delegatePartyId) {
            throw ProposalForbiddenException("only the proposing delegate can cancel proposal $proposalId")
        }
        val cancelled = proposalRepository.save(proposal.cancel(OffsetDateTime.now(clock)))
        val decisions = proposalRepository.findDecisions(setOf(proposalId))
        return WithdrawalProposalView(
            proposal = cancelled,
            approvalsReceived = decisions.count { it.approved },
            myDecision = decisions.firstOrNull { it.partyId == delegatePartyId }?.let {
                if (it.approved) ProposalActorDecision.APPROVED else ProposalActorDecision.REJECTED
            },
            canDecide = false,
        )
    }

    suspend fun listForAccount(
        accountId: UUID,
        status: WithdrawalProposalStatus?,
        callerPartyId: UUID?,
    ): List<WithdrawalProposalView> {
        val proposals = proposalRepository.findByAccountAndStatus(accountId, status)
        val visible = if (callerPartyId == null) {
            proposals
        } else {
            val owner = accountRepository.findById(accountId)?.partyId ?: return emptyList()
            proposals.filter { proposal ->
                callerPartyId == owner ||
                    callerPartyId == proposal.delegatePartyId ||
                    callerPartyId in proposal.eligibleApproverIds
            }
        }
        val decisions = proposalRepository.findDecisions(visible.mapTo(linkedSetOf()) { it.id })
            .groupBy { it.proposalId }
        val now = OffsetDateTime.now(clock)
        return visible.map { proposal ->
            val proposalDecisions = decisions[proposal.id].orEmpty()
            val mine = callerPartyId?.let { party -> proposalDecisions.firstOrNull { it.partyId == party } }
            WithdrawalProposalView(
                proposal = proposal,
                approvalsReceived = proposalDecisions.count { it.approved },
                myDecision = mine?.let {
                    if (it.approved) ProposalActorDecision.APPROVED else ProposalActorDecision.REJECTED
                },
                canDecide = callerPartyId != null &&
                    proposal.status == WithdrawalProposalStatus.PENDING &&
                    !proposal.isExpiredAt(now) &&
                    callerPartyId in proposal.eligibleApproverIds &&
                    mine == null,
            )
        }
    }

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
    // Distinct failures deliberately preserve not-found, unavailable, wrong-purpose and
    // unauthorized-representative semantics at this security boundary.
    @Suppress("ThrowsCount")
    private suspend fun verifyDecisionSca(
        ownerPartyId: UUID,
        proposal: WithdrawalProposal,
        callerPartyId: UUID,
        approve: Boolean,
        scaSessionId: UUID,
    ): UUID {
        val challenge = try {
            scaChallengeClient.getChallenge(scaSessionId)
        } catch (e: NotFoundException) {
            throw ProposalScaException("SCA challenge $scaSessionId not found", e)
        } catch (e: Exception) {
            throw ProposalScaException("SCA challenge $scaSessionId could not be verified", e)
        }
        if (challenge.purpose != SCA_PURPOSE) {
            throw ProposalScaException("SCA challenge $scaSessionId does not match the decision purpose")
        }
        val amount = proposal.amountForSca()
        val reference = SavingsWithdrawalScaReference.of(proposal.id, approve)
        if (!isDynamicallyLinked(challenge, proposal, approve, amount)) {
            throw ProposalScaException("SCA challenge $scaSessionId is not linked to this exact proposal decision")
        }
        val actorPartyId = challenge.partyId
        authorizeDecisionActor(partyMandateRepository, ownerPartyId, proposal, callerPartyId, actorPartyId)
        // A 409 is recoverable only after the signed amount/currency/reference above matched this
        // exact immutable proposal and decision. This closes the cross-service crash window: if
        // consume committed but this service failed before its DB transaction, retry completes
        // the same operation; the decision ledger prevents a second actor vote or second event.
        try {
            if (challenge.consumedAt == null) {
                scaChallengeClient.consumeChallenge(scaSessionId, actorPartyId, amount, proposal.currency, reference)
            }
        } catch (e: WebApplicationException) {
            if (e.response.status != jakarta.ws.rs.core.Response.Status.CONFLICT.statusCode) {
                throw ProposalScaException("SCA challenge $scaSessionId could not be consumed", e)
            }
        } catch (e: Exception) {
            throw ProposalScaException("SCA challenge $scaSessionId could not be consumed", e)
        }
        return actorPartyId
    }

    private fun WithdrawalProposal.amountForSca(): String {
        val fractionDigits = runCatching { java.util.Currency.getInstance(currency).defaultFractionDigits }
            .getOrElse { throw ProposalScaException("proposal $id has an invalid currency") }
        return BigDecimal.valueOf(amountMinor, fractionDigits).toPlainString()
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
