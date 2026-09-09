// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.`in`.CheckDelegationCommand
import com.openbank.delegation.application.port.`in`.CheckDelegationUseCase
import com.openbank.delegation.application.port.`in`.DelegationCandidate
import com.openbank.delegation.application.port.`in`.GetDelegationUseCase
import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.OfferDelegationUseCase
import com.openbank.delegation.application.port.`in`.PreviewDelegationCommand
import com.openbank.delegation.application.port.`in`.PreviewDelegationUseCase
import com.openbank.delegation.application.port.`in`.RespondDelegationUseCase
import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.`in`.RevokeDelegationUseCase
import com.openbank.delegation.application.port.`in`.SuspendDelegationCommand
import com.openbank.delegation.application.port.out.ApprovalGroupRepository
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibility
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.application.port.out.ScaChallengeSnapshot
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationDeclined
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.event.DelegationReinstated
import com.openbank.delegation.domain.event.DelegationRenounced
import com.openbank.delegation.domain.event.DelegationRevoked
import com.openbank.delegation.domain.event.DelegationSuspended
import com.openbank.delegation.domain.event.EventMoney
import com.openbank.delegation.domain.model.ApprovalPolicy
import com.openbank.delegation.domain.model.DelegationCapability
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class DelegationNotFoundException(id: UUID) : RuntimeException("Delegation grant not found: $id")
class DelegationNotGranteeException(id: UUID, partyId: UUID) :
    RuntimeException("Delegation $id is not granted to party $partyId")
class DelegationNotGrantorException(id: UUID, partyId: UUID) :
    RuntimeException("Delegation $id is not granted by party $partyId")
class DelegationScaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class DelegationEligibilityException(message: String) : RuntimeException(message)

/** The authenticated customer is not the party they claim to be acting as. */
class DelegationCallerMismatchException(callerPartyId: UUID, claimedPartyId: UUID) :
    RuntimeException("caller party $callerPartyId may not act as party $claimedPartyId")

/** The grantor does not own the resource, or ownership could not be established. */
class DelegationResourceOwnershipException(message: String) : RuntimeException(message)

/**
 * The request carries a constraint this platform accepts in its schema but enforces nowhere.
 *
 * Until ADR-0249 that was exactly `dailyLimit` and `monthlyLimit` (ADR-0232 D1/D6): a cumulative
 * ceiling is only a real constraint at the point where accumulated spend is OBSERVED, and no such
 * point existed. `SpendReservationService` is now that point, so the two fields are accepted again
 * — but only on a grant that can actually spend, because a cumulative ceiling on a read-only grant
 * is still a number nothing will ever count.
 *
 * `approvalPolicy` other than SOLO remains refused, unchanged: nothing counts approvals.
 */
class DelegationUnsupportedConstraintException(val code: String, message: String) : RuntimeException(message) {
    companion object {
        const val CODE_CUMULATIVE_LIMIT_UNSUPPORTED = "CUMULATIVE_LIMIT_UNSUPPORTED"
        const val CODE_APPROVAL_POLICY_UNSUPPORTED = "APPROVAL_POLICY_UNSUPPORTED"

        /** ADR-0249 D5 — a spend capability with no cumulative ceiling at all. */
        const val CODE_SPEND_WITHOUT_CEILING = "SPEND_WITHOUT_CEILING"
    }
}

@ApplicationScoped
// One aggregate service deliberately owns the complete delegation lifecycle and its shared
// authority gates. Splitting preview into another bean would duplicate the exact checks that offer
// must repeat and make the supposedly harmless path drift from the authority-creating one.
@Suppress("TooManyFunctions")
class DelegationService(
    private val delegationRepository: DelegationRepository,
    private val scaChallengeClient: ScaChallengeClient,
    private val partyEligibilityClient: PartyEligibilityClient,
    private val resourceOwnershipClient: ResourceOwnershipClient,
    private val approvalGroupRepository: ApprovalGroupRepository,
    private val nOfMEnabled: Boolean,
    private val clock: Clock,
) : OfferDelegationUseCase,
    PreviewDelegationUseCase,
    RespondDelegationUseCase,
    RevokeDelegationUseCase,
    GetDelegationUseCase,
    CheckDelegationUseCase {

    @Inject
    constructor(
        delegationRepository: DelegationRepository,
        scaChallengeClient: ScaChallengeClient,
        partyEligibilityClient: PartyEligibilityClient,
        resourceOwnershipClient: ResourceOwnershipClient,
        approvalGroupRepository: ApprovalGroupRepository,
        @ConfigProperty(name = "openbank.delegation.n-of-m-enabled", defaultValue = "false") nOfMEnabled: Boolean,
    ) : this(
        delegationRepository,
        scaChallengeClient,
        partyEligibilityClient,
        resourceOwnershipClient,
        approvalGroupRepository,
        nOfMEnabled,
        Clock.systemUTC(),
    )

    override suspend fun offer(command: OfferDelegationCommand): DelegationGrant {
        val now = OffsetDateTime.now(clock)
        val candidate = validateCandidate(command)
        // SCA last of the three gates: it SPENDS the challenge, so a request that was going to be
        // refused anyway must not cost the customer their ceremony.
        verifyAndConsumeSca(
            sessionId = command.grantScaSessionId,
            expectedPartyId = command.grantorPartyId,
            expectedPurpose = SCA_PURPOSE_GRANT,
            errorPrefix = "grant SCA",
            reference = candidate.scaReference,
        )

        val grant = DelegationGrant(
            grantorPartyId = command.grantorPartyId,
            granteePartyId = command.granteePartyId,
            // Snapshotted from the eligibility lookup that just ran — no extra call, and no new
            // authority anywhere: this service is already permitted to read both parties (#3604).
            grantorName = candidate.parties.grantorName,
            granteeName = candidate.parties.granteeName,
            resourceType = command.resourceType,
            resourceId = command.resourceId,
            capabilities = command.capabilities,
            approvalPolicy = command.approvalPolicy,
            requiredApprovals = candidate.requiredApprovals,
            approvalGroupId = candidate.approvalGroupId,
            approvalGroupRevision = candidate.approvalGroupRevision,
            perTransactionLimit = command.perTransactionLimit,
            dailyLimit = command.dailyLimit,
            monthlyLimit = command.monthlyLimit,
            exposure = command.exposure,
            validFrom = now,
            validTo = command.validTo,
            grantScaSessionId = command.grantScaSessionId,
            note = command.note,
            createdAt = now,
            updatedAt = now,
        )
        return delegationRepository.save(
            grant,
            DelegationOffered(
                aggregateId = grant.id,
                lifecycleRevision = grant.lifecycleRevision,
                grantorPartyId = grant.grantorPartyId,
                granteePartyId = grant.granteePartyId,
                resourceType = grant.resourceType,
                resourceId = grant.resourceId,
                capabilities = grant.capabilities,
                approvalPolicy = grant.approvalPolicy,
                requiredApprovals = grant.requiredApprovals,
                approvalGroupId = grant.approvalGroupId,
                approvalGroupRevision = grant.approvalGroupRevision,
                validFrom = grant.validFrom,
                validTo = grant.validTo,
                perTransactionLimit = EventMoney.from(grant.perTransactionLimit),
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun preview(
        command: PreviewDelegationCommand,
    ): com.openbank.delegation.application.port.`in`.DelegationPreview {
        val candidate = validateCandidate(command)
        // Intentionally no SCA lookup/consume, repository write or event. Preview proves only that
        // this exact draft is currently offerable; offer repeats every authoritative check.
        return com.openbank.delegation.application.port.`in`.DelegationPreview(
            candidate.scaReference,
            candidate.approvalGroupId,
            candidate.approvalGroupRevision,
            candidate.requiredApprovals,
        )
    }

    private suspend fun validateCandidate(command: DelegationCandidate): ValidatedCandidate {
        requireCallerIs(command.callerPartyId, command.grantorPartyId)
        rejectUnenforcedCeilings(command)
        val approval = resolveApprovalPolicy(command)
        verifyResourceOwnership(command)
        return ValidatedCandidate(
            parties = verifyEligibility(command),
            approvalGroupId = approval.groupId,
            approvalGroupRevision = approval.revision,
            requiredApprovals = approval.threshold,
            scaReference = scaReference(command, approval),
        )
    }

    override suspend fun accept(
        delegationId: UUID,
        granteePartyId: UUID,
        scaSessionId: UUID,
        callerPartyId: CallerPartyId,
    ): DelegationGrant {
        requireCallerIs(callerPartyId, granteePartyId)
        val grant = loadForGrantee(delegationId, granteePartyId)
        verifyAndConsumeSca(
            sessionId = scaSessionId,
            expectedPartyId = granteePartyId,
            expectedPurpose = SCA_PURPOSE_ACCEPT,
            errorPrefix = "accept SCA",
        )
        val accepted = grant.accept(scaSessionId, OffsetDateTime.now(clock))
        return delegationRepository.save(
            accepted,
            DelegationActivated(
                aggregateId = accepted.id,
                lifecycleRevision = accepted.lifecycleRevision,
                grantorPartyId = accepted.grantorPartyId,
                granteePartyId = accepted.granteePartyId,
                resourceType = accepted.resourceType,
                resourceId = accepted.resourceId,
                capabilities = accepted.capabilities,
                approvalPolicy = accepted.approvalPolicy,
                requiredApprovals = accepted.requiredApprovals,
                approvalGroupId = accepted.approvalGroupId,
                approvalGroupRevision = accepted.approvalGroupRevision,
                validFrom = accepted.validFrom,
                validTo = accepted.validTo,
                perTransactionLimit = EventMoney.from(accepted.perTransactionLimit),
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun decline(
        delegationId: UUID,
        granteePartyId: UUID,
        callerPartyId: CallerPartyId,
    ): DelegationGrant {
        requireCallerIs(callerPartyId, granteePartyId)
        val grant = loadForGrantee(delegationId, granteePartyId)
        val declined = grant.decline(OffsetDateTime.now(clock))
        return delegationRepository.save(
            declined,
            DelegationDeclined(
                aggregateId = declined.id,
                lifecycleRevision = declined.lifecycleRevision,
                grantorPartyId = declined.grantorPartyId,
                granteePartyId = declined.granteePartyId,
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun renounce(
        delegationId: UUID,
        granteePartyId: UUID,
        callerPartyId: CallerPartyId,
    ): DelegationGrant {
        requireCallerIs(callerPartyId, granteePartyId)
        val grant = loadForGrantee(delegationId, granteePartyId)
        val renounced = grant.renounce(OffsetDateTime.now(clock))
        return delegationRepository.save(
            renounced,
            DelegationRenounced(
                aggregateId = renounced.id,
                lifecycleRevision = renounced.lifecycleRevision,
                grantorPartyId = renounced.grantorPartyId,
                granteePartyId = renounced.granteePartyId,
                resourceType = renounced.resourceType,
                resourceId = renounced.resourceId,
                occurredAt = clock.instant(),
            ),
        )
    }

    /**
     * ADR-0232 D4: revocation is unilateral and immediate. The grantor (or the bank,
     * via the operator channel with its own OPA rule) can always revoke; the grantee
     * never blocks it. OFFERED grants are revocable too — withdrawing an unanswered
     * invitation is the same act.
     */
    override suspend fun revoke(command: RevokeDelegationCommand): DelegationGrant {
        val grant = delegationRepository.findById(command.delegationId)
            ?: throw DelegationNotFoundException(command.delegationId)
        // Unilateral for the GRANTOR (and for the bank), not for everyone: without this, any
        // authenticated caller could revoke any grant — a free denial-of-service on someone
        // else's standing access, recorded against a `closedBy` of their choosing.
        if (!command.bankInitiated && command.revokedBy != grant.grantorPartyId) {
            throw DelegationNotGrantorException(command.delegationId, command.revokedBy)
        }
        val revoked = grant.revoke(command.revokedBy, command.reason, OffsetDateTime.now(clock))
        return delegationRepository.save(
            revoked,
            DelegationRevoked(
                aggregateId = revoked.id,
                lifecycleRevision = revoked.lifecycleRevision,
                grantorPartyId = revoked.grantorPartyId,
                granteePartyId = revoked.granteePartyId,
                resourceType = revoked.resourceType,
                resourceId = revoked.resourceId,
                capabilities = revoked.capabilities,
                reason = command.reason,
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun suspend(command: SuspendDelegationCommand): DelegationGrant {
        val grant = delegationRepository.findById(command.delegationId)
            ?: throw DelegationNotFoundException(command.delegationId)
        val suspended = grant.suspend(command.reason, OffsetDateTime.now(clock))
        return delegationRepository.save(
            suspended,
            DelegationSuspended(
                aggregateId = suspended.id,
                lifecycleRevision = suspended.lifecycleRevision,
                grantorPartyId = suspended.grantorPartyId,
                granteePartyId = suspended.granteePartyId,
                resourceType = suspended.resourceType,
                resourceId = suspended.resourceId,
                capabilities = suspended.capabilities,
                reason = command.reason,
                occurredAt = clock.instant(),
            ),
        )
    }

    override suspend fun reinstate(delegationId: UUID): DelegationGrant {
        val grant = delegationRepository.findById(delegationId)
            ?: throw DelegationNotFoundException(delegationId)
        val reinstated = grant.reinstate(OffsetDateTime.now(clock))
        return delegationRepository.save(
            reinstated,
            DelegationReinstated(
                aggregateId = reinstated.id,
                lifecycleRevision = reinstated.lifecycleRevision,
                grantorPartyId = reinstated.grantorPartyId,
                granteePartyId = reinstated.granteePartyId,
                resourceType = reinstated.resourceType,
                resourceId = reinstated.resourceId,
                capabilities = reinstated.capabilities,
                approvalPolicy = reinstated.approvalPolicy,
                requiredApprovals = reinstated.requiredApprovals,
                approvalGroupId = reinstated.approvalGroupId,
                approvalGroupRevision = reinstated.approvalGroupRevision,
                validFrom = reinstated.validFrom,
                validTo = reinstated.validTo,
                perTransactionLimit = EventMoney.from(reinstated.perTransactionLimit),
                occurredAt = clock.instant(),
            ),
        )
    }

    /**
     * DelegationNotFoundException — not a mismatch error — when a customer-scoped caller asks for
     * a grant they are not party to: the endpoint must not double as an existence oracle for
     * other people's sharing relationships. Same reason AccountResource answers 404 rather than
     * 403 on its ownership guard.
     */
    override suspend fun getDelegation(delegationId: UUID, callerPartyId: CallerPartyId): DelegationGrant {
        val grant = delegationRepository.findById(delegationId) ?: throw DelegationNotFoundException(delegationId)
        if (callerPartyId != null && callerPartyId != grant.grantorPartyId && callerPartyId != grant.granteePartyId) {
            throw DelegationNotFoundException(delegationId)
        }
        return grant
    }

    override suspend fun listByGrantor(grantorPartyId: UUID, callerPartyId: CallerPartyId): List<DelegationGrant> {
        requireCallerIs(callerPartyId, grantorPartyId)
        return delegationRepository.findByGrantorId(grantorPartyId)
    }

    override suspend fun listByGrantee(granteePartyId: UUID, callerPartyId: CallerPartyId): List<DelegationGrant> {
        requireCallerIs(callerPartyId, granteePartyId)
        return delegationRepository.findByGranteeId(granteePartyId)
    }

    override suspend fun check(command: CheckDelegationCommand): DelegationCheckResult {
        val now = OffsetDateTime.now(clock)
        val grant = delegationRepository
            .findActiveByGranteeAndResource(command.granteePartyId, command.resourceType, command.resourceId)
            .firstOrNull { it.isActiveOn(now) && it.covers(command.capability, command.amount) }
            ?: return DelegationCheckResult.Denied(
                "no active delegation covers ${command.capability} on " +
                    "${command.resourceType}/${command.resourceId} for party ${command.granteePartyId}",
                "DELEGATION_NOT_COVERED",
            )
        return DelegationCheckResult.Allowed(grant)
    }

    private suspend fun loadForGrantee(delegationId: UUID, granteePartyId: UUID): DelegationGrant {
        val grant = delegationRepository.findById(delegationId)
            ?: throw DelegationNotFoundException(delegationId)
        if (grant.granteePartyId != granteePartyId) {
            throw DelegationNotGranteeException(delegationId, granteePartyId)
        }
        return grant
    }

    /**
     * A customer-scoped call may only act as the party the edge authenticated. `null` = the call
     * did not come from the customer channel (operator/back-office), gated by role and OPA
     * instead — see [CallerPartyId].
     */
    private fun requireCallerIs(callerPartyId: CallerPartyId, claimedPartyId: UUID) {
        if (callerPartyId != null && callerPartyId != claimedPartyId) {
            throw DelegationCallerMismatchException(callerPartyId, claimedPartyId)
        }
    }

    /**
     * ADR-0249 D3/D5, replacing #3613's blanket refusal of `dailyLimit` / `monthlyLimit`.
     *
     * The refusal was correct while nothing counted: a "5 000 Kč/den" the platform does not honour
     * is worse than no feature, because the customer acts on it. `SpendReservationService` now
     * counts, so the two ceilings are accepted — under two rules, both checked on the WRITE path
     * only, ahead of the ownership lookup, the eligibility call and above all ahead of the SCA
     * consume, so a request that will be refused anyway does not cost the customer their ceremony:
     *
     *  1. a cumulative ceiling requires a money-moving capability. On a read-only grant nothing
     *     will ever reserve against it, so it would be exactly the unenforced number #3613 refused;
     *  2. (D5) `ACCOUNT_INITIATE_PAYMENT` requires at least one of the two. "Unlimited access to
     *     someone else's account" is a product decision no bank should make by omission.
     *
     * Rule 2 is deliberately scoped to `ACCOUNT_INITIATE_PAYMENT`, the capability D5 names, and not
     * to every [DelegationGrant.EXECUTION_CAPABILITIES] entry: `SAVINGS_WITHDRAW` grants are
     * already live without ceilings, and silently invalidating the shape they were offered under
     * would be an unrelated behaviour change smuggled in on this one.
     *
     * Both rules live here rather than in the aggregate's `init` for a specific reason: grants
     * written before ADR-0249 carry `ACCOUNT_INITIATE_PAYMENT` and no ceiling at all — #3613 made
     * sure of it — so an invariant in the constructor would make every one of them unrehydratable
     * the moment this deploys.
     */
    private fun rejectUnenforcedCeilings(command: DelegationCandidate) {
        val hasCumulativeCeiling = command.dailyLimit != null || command.monthlyLimit != null
        val canSpend = command.capabilities.any { it in DelegationGrant.EXECUTION_CAPABILITIES }
        if (hasCumulativeCeiling && !canSpend) {
            throw DelegationUnsupportedConstraintException(
                code = DelegationUnsupportedConstraintException.CODE_CUMULATIVE_LIMIT_UNSUPPORTED,
                message = "dailyLimit/monthlyLimit cannot be accepted on a grant with no money-moving " +
                    "capability: nothing reserves spend against it, so the ceiling would never be applied " +
                    "to anything. Omit the field, or grant a spending capability (ADR-0249 D3).",
            )
        }
        if (DelegationCapability.ACCOUNT_INITIATE_PAYMENT in command.capabilities && !hasCumulativeCeiling) {
            throw DelegationUnsupportedConstraintException(
                code = DelegationUnsupportedConstraintException.CODE_SPEND_WITHOUT_CEILING,
                message = "ACCOUNT_INITIATE_PAYMENT requires a dailyLimit or a monthlyLimit: a delegate who may " +
                    "initiate payments with no cumulative ceiling has unlimited access to someone else's " +
                    "account, which is not something this platform grants by omission (ADR-0249 D5).",
            )
        }
    }

    /**
     * ADR-0232 D8's co-signing promise, refused for the same reason as the cumulative ceilings:
     * nothing counts approvals against a grant.
     *
     * `approvalPolicy` is accepted, checked for self-consistency (N_OF_M demands
     * `requiredApprovals >= 2`), persisted, echoed and rendered — and never read by a decision.
     * `DelegationGrant.covers` consults capability and `perTransactionLimit` only;
     * `DelegationOffered` does not carry the policy; account-service's delegation projection has
     * no column for it. So the one flow that could honour it — `SavingsProposalService.decide`,
     * the D8 maker-checker — releases the withdrawal on a SINGLE owner decision no matter what
     * the grantor chose. "Oba rodiče musí schválit výběr" is a number nobody counts.
     *
     * SOLO stays accepted: it is the default and it promises no second approver, so it is the one
     * value that is honest today. Delivering the rest means replicating the policy onto the
     * projection and counting decisions where the money moves — in that order, or the counter is
     * a second unread field.
     */
    private suspend fun resolveApprovalPolicy(command: DelegationCandidate): ResolvedApproval = when (
        command.approvalPolicy
    ) {
        ApprovalPolicy.SOLO -> {
            require(command.approvalGroupId == null && command.requiredApprovals == null) {
                "SOLO approval must not reference an approval group or threshold"
            }
            ResolvedApproval(null, null, null)
        }

        ApprovalPolicy.N_OF_M -> {
            if (!nOfMEnabled) {
                throw DelegationUnsupportedConstraintException(
                    code = DelegationUnsupportedConstraintException.CODE_APPROVAL_POLICY_UNSUPPORTED,
                    message = "N_OF_M admission is not enabled in this environment",
                )
            }
            if (command.resourceType != com.openbank.delegation.domain.model.DelegationResourceType.SAVINGS_GOAL ||
                command.capabilities != setOf(DelegationCapability.SAVINGS_PROPOSE_WITHDRAW)
            ) {
                throw DelegationUnsupportedConstraintException(
                    code = DelegationUnsupportedConstraintException.CODE_APPROVAL_POLICY_UNSUPPORTED,
                    message = "N_OF_M is currently enforced only for a SAVINGS_GOAL grant containing exactly " +
                        "SAVINGS_PROPOSE_WITHDRAW; combining it with an unenforced capability is refused",
                )
            }
            val groupId = command.approvalGroupId ?: throw IllegalArgumentException(
                "N_OF_M approval requires approvalGroupId",
            )
            val group = approvalGroupRepository.findById(groupId)
                ?: throw DelegationUnsupportedConstraintException(
                    DelegationUnsupportedConstraintException.CODE_APPROVAL_POLICY_UNSUPPORTED,
                    "approval group $groupId does not exist",
                )
            if (!group.active || group.ownerPartyId != command.grantorPartyId || group.threshold < 2) {
                throw DelegationUnsupportedConstraintException(
                    DelegationUnsupportedConstraintException.CODE_APPROVAL_POLICY_UNSUPPORTED,
                    "approval group $groupId must be active, owned by the grantor, and require at least two actors",
                )
            }
            if (command.requiredApprovals != null && command.requiredApprovals != group.threshold) {
                throw IllegalArgumentException("requiredApprovals is server-derived from approvalGroupId")
            }
            ResolvedApproval(group.id, group.revision, group.threshold)
        }

        else -> {
            throw DelegationUnsupportedConstraintException(
                code = DelegationUnsupportedConstraintException.CODE_APPROVAL_POLICY_UNSUPPORTED,
                message = "approvalPolicy ${command.approvalPolicy} has no enforcing operation resolver",
            )
        }
    }

    /**
     * ADR-0232 threat model T1. The grant is authority in itself once it reaches a product
     * service's projection, so if nobody checks that the grantor owns the resource, a grant
     * naming a stranger's account grants access to that account. Fail closed on UNVERIFIABLE:
     * an ownership lookup we could not perform is not permission.
     */
    private suspend fun verifyResourceOwnership(command: DelegationCandidate) {
        val verdict = resourceOwnershipClient.verifyOwnership(
            command.grantorPartyId,
            command.resourceType,
            command.resourceId,
        )
        when (verdict) {
            OwnershipVerdict.OWNED -> Unit

            OwnershipVerdict.NOT_OWNED -> throw DelegationResourceOwnershipException(
                "grantor ${command.grantorPartyId} does not own " +
                    "${command.resourceType}/${command.resourceId}",
            )

            OwnershipVerdict.UNVERIFIABLE -> throw DelegationResourceOwnershipException(
                "ownership of ${command.resourceType}/${command.resourceId} could not be established — " +
                    "refusing to mint a grant over an unverified resource",
            )
        }
    }

    /**
     * ADR-0232 D4: both ends of the ceremony are SCA-bound and the challenge must name
     * the same party that acts — an accept challenge completed by the grantor must not
     * activate a grant for the grantee.
     *
     * The challenge is then SPENT via sca-service's compare-and-consume gate (RTS Art. 5
     * single-use). Checking `status == "COMPLETED"` is not a substitute: completion is a fact
     * that stays true, so one ceremony authorised unlimited grants of arbitrary scope for as
     * long as the challenge row existed. Consume is atomic (compare-and-set on `consumedAt`),
     * so two concurrent offers on one challenge cannot both win.
     */
    private suspend fun verifyAndConsumeSca(
        sessionId: UUID,
        expectedPartyId: UUID,
        expectedPurpose: String,
        errorPrefix: String,
        reference: String? = null,
    ) {
        val challenge = loadChallenge(sessionId, errorPrefix)
        requireChallengeMatches(challenge, expectedPartyId, expectedPurpose, errorPrefix)
        spendChallenge(sessionId, expectedPartyId, errorPrefix, reference)
    }

    @Suppress("TooGenericExceptionCaught") // any failure to reach sca-service must refuse the act
    private suspend fun loadChallenge(sessionId: UUID, errorPrefix: String): ScaChallengeSnapshot = try {
        scaChallengeClient.getChallenge(sessionId)
    } catch (e: NotFoundException) {
        throw DelegationScaException("$errorPrefix challenge $sessionId not found", e)
    } catch (e: Exception) {
        throw DelegationScaException("$errorPrefix challenge $sessionId could not be verified", e)
    }

    private fun requireChallengeMatches(
        challenge: ScaChallengeSnapshot,
        expectedPartyId: UUID,
        expectedPurpose: String,
        errorPrefix: String,
    ) {
        if (challenge.partyId != expectedPartyId || challenge.purpose != expectedPurpose) {
            throw DelegationScaException("$errorPrefix challenge ${challenge.id} does not match party or purpose")
        }
        // Deliberately NOT asserting status == "COMPLETED" here. A decoupled challenge
        // (PUSH_NOTIFICATION / BIOMETRIC) sits at PENDING while holding a signature-verified
        // device decision: `verify()` promotes it, and NOTHING a customer can reach calls verify —
        // customer-edge exposes create / read / decision only. Payments work because the edge's own
        // scaGate calls `consume`, which resolves a pending decoupled challenge itself before
        // spending it ("resolve it now rather than refusing"). This pre-check ran BEFORE consume and
        // rejected exactly the challenges consume would have legitimately resolved, so every
        // customer-driven offer and accept failed with "challenge is not completed" — the whole
        // ceremony was unreachable from the app.
        //
        // Approval is still enforced, and by the component that owns it: consume throws
        // ScaChallengeNotApprovedException unless the challenge reaches COMPLETED, checks the party,
        // refuses an already-spent challenge, and enforces dynamic linking. Asserting completion
        // here as well only duplicated a weaker copy of that check at the one moment it could not
        // yet be true.
    }

    @Suppress("TooGenericExceptionCaught") // includes sca-service's 409 for an already-spent challenge
    private suspend fun spendChallenge(
        sessionId: UUID,
        expectedPartyId: UUID,
        errorPrefix: String,
        reference: String?,
    ) {
        try {
            scaChallengeClient.consumeChallenge(sessionId, expectedPartyId, reference)
        } catch (e: Exception) {
            // The 409 is the replay this whole path exists to stop.
            throw DelegationScaException("$errorPrefix challenge $sessionId could not be spent", e)
        }
    }

    /**
     * ADR-0232 D5: delegates are real, screened parties. Fail CLOSED on any doubt —
     * this endpoint mints payment rights, so an eligibility service outage must block
     * offers, never wave them through. KYC requirements: FULL for execution
     * capabilities (they move money), BASIC for everything read-only/propose-only.
     */
    private suspend fun verifyEligibility(command: DelegationCandidate): CounterpartyNames {
        val grantor = partyEligibilityClient.eligibilityOf(command.grantorPartyId)
        if (!grantor.active) {
            throw DelegationEligibilityException("grantor party ${command.grantorPartyId} is not active")
        }
        val grantee = partyEligibilityClient.eligibilityOf(command.granteePartyId)
        if (!grantee.active) {
            throw DelegationEligibilityException("grantee party ${command.granteePartyId} is not active")
        }
        requireGranteeKyc(command, grantee)
        return CounterpartyNames(grantorName = grantor.displayName, granteeName = grantee.displayName)
    }

    /**
     * Split out of [verifyEligibility] only because that function now RETURNS the counterparty
     * labels (issue #3604): detekt's `ThrowsCount` excludes trailing guard clauses, and a
     * function with a real return value has none — so the same three unchanged throws crossed the
     * threshold. The gate is behaviourally identical.
     */
    private fun requireGranteeKyc(command: DelegationCandidate, grantee: PartyEligibility) {
        val needsFullKyc = command.capabilities.any { it in DelegationGrant.EXECUTION_CAPABILITIES }
        val requiredKyc = if (needsFullKyc) "FULL" else "BASIC"
        if (KYC_RANK.getValue(grantee.kycLevel) < KYC_RANK.getValue(requiredKyc)) {
            throw DelegationEligibilityException(
                "grantee party ${command.granteePartyId} KYC level ${grantee.kycLevel} " +
                    "is below required $requiredKyc for capabilities ${command.capabilities}",
            )
        }
    }

    /** The two labels the eligibility lookup yields as a by-product (issue #3604). */
    private data class CounterpartyNames(val grantorName: String?, val granteeName: String?)

    private data class ResolvedApproval(val groupId: UUID?, val revision: Long?, val threshold: Int?)

    private data class ValidatedCandidate(
        val parties: CounterpartyNames,
        val approvalGroupId: UUID?,
        val approvalGroupRevision: Long?,
        val requiredApprovals: Int?,
        val scaReference: String,
    )

    private fun scaReference(command: DelegationCandidate, approval: ResolvedApproval): String {
        val fields = listOf(
            "delegation-grant-v1",
            command.grantorPartyId.toString(),
            command.granteePartyId.toString(),
            command.resourceType.name,
            command.resourceId.toString(),
            command.capabilities.map { it.name }.sorted().joinToString(","),
            command.approvalPolicy.name,
            approval.groupId?.toString().orEmpty(),
            approval.revision?.toString().orEmpty(),
            approval.threshold?.toString().orEmpty(),
            command.perTransactionLimit.canonical(),
            command.dailyLimit.canonical(),
            command.monthlyLimit.canonical(),
            command.exposure?.let {
                listOf(it.redactionRules.sorted().joinToString(","), it.maxViews, it.watermark, it.allowDownload)
                    .joinToString("|")
            }.orEmpty(),
            command.validTo?.toInstant()?.toString().orEmpty(),
        )
        val canonical = fields.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return "delegation-grant-v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun com.openbank.libs.domain.money.Money?.canonical(): String = this?.let {
        "${it.amount.stripTrailingZeros().toPlainString()}|${it.currency.code}"
    }.orEmpty()

    private companion object {
        const val SCA_PURPOSE_GRANT = "DELEGATION_GRANT"
        const val SCA_PURPOSE_ACCEPT = "DELEGATION_ACCEPT"

        val KYC_RANK = mapOf("NONE" to 0, "BASIC" to 1, "ENHANCED" to 2, "FULL" to 3)
    }
}
