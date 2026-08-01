// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.application.usecase

import com.openbank.delegation.application.port.`in`.CallerPartyId
import com.openbank.delegation.application.port.`in`.CheckDelegationCommand
import com.openbank.delegation.application.port.`in`.CheckDelegationUseCase
import com.openbank.delegation.application.port.`in`.GetDelegationUseCase
import com.openbank.delegation.application.port.`in`.OfferDelegationCommand
import com.openbank.delegation.application.port.`in`.OfferDelegationUseCase
import com.openbank.delegation.application.port.`in`.RespondDelegationUseCase
import com.openbank.delegation.application.port.`in`.RevokeDelegationCommand
import com.openbank.delegation.application.port.`in`.RevokeDelegationUseCase
import com.openbank.delegation.application.port.`in`.SuspendDelegationCommand
import com.openbank.delegation.application.port.out.DelegationRepository
import com.openbank.delegation.application.port.out.OwnershipVerdict
import com.openbank.delegation.application.port.out.PartyEligibilityClient
import com.openbank.delegation.application.port.out.ResourceOwnershipClient
import com.openbank.delegation.application.port.out.ScaChallengeClient
import com.openbank.delegation.domain.event.DelegationActivated
import com.openbank.delegation.domain.event.DelegationDeclined
import com.openbank.delegation.domain.event.DelegationOffered
import com.openbank.delegation.domain.event.DelegationReinstated
import com.openbank.delegation.domain.event.DelegationRenounced
import com.openbank.delegation.domain.event.DelegationRevoked
import com.openbank.delegation.domain.event.DelegationSuspended
import com.openbank.delegation.domain.model.DelegationCheckResult
import com.openbank.delegation.domain.model.DelegationGrant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.NotFoundException
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

@ApplicationScoped
class DelegationService(
    private val delegationRepository: DelegationRepository,
    private val scaChallengeClient: ScaChallengeClient,
    private val partyEligibilityClient: PartyEligibilityClient,
    private val resourceOwnershipClient: ResourceOwnershipClient,
    private val clock: Clock,
) : OfferDelegationUseCase,
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
    ) : this(
        delegationRepository,
        scaChallengeClient,
        partyEligibilityClient,
        resourceOwnershipClient,
        Clock.systemUTC(),
    )

    override suspend fun offer(command: OfferDelegationCommand): DelegationGrant {
        val now = OffsetDateTime.now(clock)
        requireCallerIs(command.callerPartyId, command.grantorPartyId)
        verifyResourceOwnership(command)
        verifyEligibility(command)
        // SCA last of the three gates: it SPENDS the challenge, so a request that was going to be
        // refused anyway must not cost the customer their ceremony.
        verifyAndConsumeSca(
            sessionId = command.grantScaSessionId,
            expectedPartyId = command.grantorPartyId,
            expectedPurpose = SCA_PURPOSE_GRANT,
            errorPrefix = "grant SCA",
        )

        val grant = DelegationGrant(
            grantorPartyId = command.grantorPartyId,
            granteePartyId = command.granteePartyId,
            resourceType = command.resourceType,
            resourceId = command.resourceId,
            capabilities = command.capabilities,
            approvalPolicy = command.approvalPolicy,
            requiredApprovals = command.requiredApprovals,
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
                grantorPartyId = grant.grantorPartyId,
                granteePartyId = grant.granteePartyId,
                resourceType = grant.resourceType,
                resourceId = grant.resourceId,
                capabilities = grant.capabilities,
                occurredAt = clock.instant(),
            ),
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
                grantorPartyId = accepted.grantorPartyId,
                granteePartyId = accepted.granteePartyId,
                resourceType = accepted.resourceType,
                resourceId = accepted.resourceId,
                capabilities = accepted.capabilities,
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
                grantorPartyId = reinstated.grantorPartyId,
                granteePartyId = reinstated.granteePartyId,
                resourceType = reinstated.resourceType,
                resourceId = reinstated.resourceId,
                capabilities = reinstated.capabilities,
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
     * ADR-0232 threat model T1. The grant is authority in itself once it reaches a product
     * service's projection, so if nobody checks that the grantor owns the resource, a grant
     * naming a stranger's account grants access to that account. Fail closed on UNVERIFIABLE:
     * an ownership lookup we could not perform is not permission.
     */
    private suspend fun verifyResourceOwnership(command: OfferDelegationCommand) {
        when (resourceOwnershipClient.verifyOwnership(
            command.grantorPartyId,
            command.resourceType,
            command.resourceId,
        )) {
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
    ) {
        val challenge = try {
            scaChallengeClient.getChallenge(sessionId)
        } catch (e: NotFoundException) {
            throw DelegationScaException("$errorPrefix challenge $sessionId not found", e)
        } catch (e: Exception) {
            throw DelegationScaException("$errorPrefix challenge $sessionId could not be verified", e)
        }
        if (challenge.partyId != expectedPartyId || challenge.purpose != expectedPurpose) {
            throw DelegationScaException("$errorPrefix challenge $sessionId does not match party or purpose")
        }
        if (challenge.status != "COMPLETED") {
            throw DelegationScaException("$errorPrefix challenge $sessionId is not completed")
        }
        try {
            scaChallengeClient.consumeChallenge(sessionId, expectedPartyId)
        } catch (e: Exception) {
            // Includes sca-service's 409 for an already-consumed challenge — the replay this
            // whole path exists to stop.
            throw DelegationScaException("$errorPrefix challenge $sessionId could not be spent", e)
        }
    }

    /**
     * ADR-0232 D5: delegates are real, screened parties. Fail CLOSED on any doubt —
     * this endpoint mints payment rights, so an eligibility service outage must block
     * offers, never wave them through. KYC requirements: FULL for execution
     * capabilities (they move money), BASIC for everything read-only/propose-only.
     */
    private suspend fun verifyEligibility(command: OfferDelegationCommand) {
        val grantor = partyEligibilityClient.eligibilityOf(command.grantorPartyId)
        if (!grantor.active) {
            throw DelegationEligibilityException("grantor party ${command.grantorPartyId} is not active")
        }
        val grantee = partyEligibilityClient.eligibilityOf(command.granteePartyId)
        if (!grantee.active) {
            throw DelegationEligibilityException("grantee party ${command.granteePartyId} is not active")
        }
        val needsFullKyc = command.capabilities.any { it in DelegationGrant.EXECUTION_CAPABILITIES }
        val requiredKyc = if (needsFullKyc) "FULL" else "BASIC"
        if (KYC_RANK.getValue(grantee.kycLevel) < KYC_RANK.getValue(requiredKyc)) {
            throw DelegationEligibilityException(
                "grantee party ${command.granteePartyId} KYC level ${grantee.kycLevel} " +
                    "is below required $requiredKyc for capabilities ${command.capabilities}",
            )
        }
    }

    private companion object {
        const val SCA_PURPOSE_GRANT = "DELEGATION_GRANT"
        const val SCA_PURPOSE_ACCEPT = "DELEGATION_ACCEPT"

        val KYC_RANK = mapOf("NONE" to 0, "BASIC" to 1, "ENHANCED" to 2, "FULL" to 3)
    }
}
