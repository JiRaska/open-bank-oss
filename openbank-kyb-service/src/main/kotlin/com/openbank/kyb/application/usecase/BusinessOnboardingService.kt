// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import com.openbank.kyb.application.port.`in`.ClaimInvitationCommand
import com.openbank.kyb.application.port.`in`.InviteCosignersCommand
import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.`in`.MatchInitiatorCommand
import com.openbank.kyb.application.port.`in`.RejectCaseCommand
import com.openbank.kyb.application.port.`in`.ResolveReviewCommand
import com.openbank.kyb.application.port.`in`.SignCommand
import com.openbank.kyb.application.port.`in`.StartCaseCommand
import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.BusinessOnboardingWorkflowPort
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.InvitationTokens
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.KybEvents
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.SignerStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.util.UUID

class CaseNotFoundException(id: UUID) : RuntimeException("business onboarding case not found: $id")
class CaseCallerMismatchException(message: String) : RuntimeException(message)
class InvitationNotFoundException : RuntimeException("no open invitation for this token")

/**
 * The business onboarding use case (ADR-0284 D1/D3). Every transition goes through the aggregate
 * and is persisted with its event in one transaction; party-service calls happen BEFORE the local
 * write they are evidence for, so a party-service failure leaves the case where it was.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one method per state transition; the count is the state machine's, not the class's
class BusinessOnboardingService : BusinessOnboardingUseCase {

    @Inject lateinit var cases: BusinessOnboardingCaseRepository

    @Inject lateinit var lookup: RegistryLookupService

    @Inject lateinit var parties: PartyGateway

    @Inject lateinit var tokens: InvitationTokens

    @Inject lateinit var metrics: KybMetricsPort

    @Inject lateinit var timers: BusinessOnboardingWorkflowPort

    @Inject lateinit var clock: Clock

    private val log = Logger.getLogger(BusinessOnboardingService::class.java)

    override suspend fun start(cmd: StartCaseCommand): BusinessOnboardingCase {
        val identifier = LegalEntityIdentifier.of(cmd.scheme, cmd.identifier)
        cases.findOpenByIdentifier(identifier)?.let { existing ->
            // The same person retrying is idempotent; another person starting the same entity joins
            // the open case as a would-be signer via invitation, never by opening a second case.
            if (existing.initiatorPartyId == cmd.initiatorPartyId) return existing
            throw CaseCallerMismatchException(
                "an onboarding for ${identifier.scheme.displayName} ${identifier.value} is already open",
            )
        }
        val now = Instant.now(clock)
        val started = BusinessOnboardingCase.start(UUID.randomUUID(), identifier, cmd.initiatorPartyId, now)
        val extract = lookup.lookup(identifier, LookupCommand(cmd.scheme, cmd.identifier, cmd.declared))
            ?: return cases.save(
                started.copy(
                    status = CaseStatus.MANUAL_REVIEW,
                    reviewReason = "no register record for ${identifier.scheme.displayName} ${identifier.value}",
                ),
                KybEvents.reviewRequired(started, now),
            ).also {
                metrics.caseStarted(it.identifier.scheme.name, it.status.name)
                armTimers(it)
            }
        val verified = started.registryVerified(extract, now)
        val withParty = if (verified.status == CaseStatus.REGISTRY_VERIFIED) {
            verified.entityPartyCreated(parties.createEntityParty(entityPartyRequest(verified.id, extract)), now)
        } else {
            verified
        }
        val event = if (withParty.status ==
            CaseStatus.REGISTRY_VERIFIED
        ) {
            KybEvents.registryVerified(withParty, now)
        } else {
            KybEvents.reviewRequired(withParty, now)
        }
        return cases.save(withParty, event).also {
            metrics.caseStarted(it.identifier.scheme.name, it.status.name)
            armTimers(it)
        }
    }

    override suspend fun get(caseId: UUID): BusinessOnboardingCase =
        cases.findById(caseId) ?: throw CaseNotFoundException(caseId)

    override suspend fun listForParty(partyId: UUID): List<BusinessOnboardingCase> = cases.findInvolving(partyId)

    override suspend fun listByStatus(status: CaseStatus, page: Int, size: Int): List<BusinessOnboardingCase> =
        cases.listByStatus(status, page, size)

    override suspend fun matchInitiator(cmd: MatchInitiatorCommand): BusinessOnboardingCase {
        val case = ownedBy(cmd.caseId, cmd.callerPartyId)
        val now = Instant.now(clock)
        val matched = case.initiatorMatched(cmd.representativeIndex, cmd.claimedName, cmd.dateOfBirth, now)
        val event = if (matched.status == CaseStatus.MANUAL_REVIEW) KybEvents.reviewRequired(matched, now) else null
        return cases.update(matched, event).also(::armTimers)
    }

    override suspend fun inviteCosigners(cmd: InviteCosignersCommand): BusinessOnboardingCase {
        val case = ownedBy(cmd.caseId, cmd.callerPartyId)
        val now = Instant.now(clock)
        val distinct = cmd.representativeIndexes.distinct().filter { it != case.initiator?.representativeIndex }
        val invited = case.cosignersInvited(distinct, List(distinct.size) { tokens.next() }, now)
        val newSigners = invited.signers.filter { s ->
            s.status == SignerStatus.INVITED &&
                case.signers.none { it.id == s.id }
        }
        var saved = cases.update(
            invited,
            newSigners.firstOrNull()?.let {
                KybEvents.signerInvited(invited, it, now, cmd.callerPartyId.toString())
            },
        )
        newSigners.drop(1).forEach {
            saved =
                cases.update(saved, KybEvents.signerInvited(saved, it, now, cmd.callerPartyId.toString()))
        }
        return saved.also(::armTimers)
    }

    override suspend fun claimInvitation(cmd: ClaimInvitationCommand): BusinessOnboardingCase {
        val case = cases.findByInvitationToken(cmd.token) ?: throw InvitationNotFoundException()
        val now = Instant.now(clock)
        val identified = case.signerIdentified(cmd.token, cmd.partyId, now)
        val signer = identified.signers.first { it.partyId == cmd.partyId }
        return cases.update(identified, KybEvents.signerIdentified(identified, signer, now)).also(::armTimers)
    }

    override suspend fun sign(cmd: SignCommand): BusinessOnboardingCase {
        val case = get(cmd.caseId)
        val now = Instant.now(clock)
        val signed = case.signed(cmd.signerPartyId, cmd.signatureRef, now)
        val event = when (signed.status) {
            CaseStatus.SIGNED, CaseStatus.ACTIVE -> KybEvents.agreementSigned(signed, now, cmd.signerPartyId.toString())
            else -> null
        }
        if (signed.status == CaseStatus.SIGNED || signed.status == CaseStatus.ACTIVE) grantMandates(signed)
        val saved = cases.update(signed, event)
        if (saved.status == CaseStatus.ACTIVE) cases.update(saved, KybEvents.completed(saved, now))
        return saved.also(::armTimers)
    }

    override suspend fun abandon(caseId: UUID, callerPartyId: UUID): BusinessOnboardingCase {
        val case = ownedBy(caseId, callerPartyId)
        val now = Instant.now(clock)
        val abandoned = case.abandoned(now)
        return cases.update(abandoned, KybEvents.abandoned(abandoned, now, callerPartyId.toString())).also(::armTimers)
    }

    override suspend fun resolveReview(cmd: ResolveReviewCommand): BusinessOnboardingCase {
        val case = get(cmd.caseId)
        val now = Instant.now(clock)
        var resolved = case.reviewResolved(cmd.requiredSignatures, now)
        if (resolved.entityPartyId == null && resolved.extract != null) {
            resolved =
                resolved.entityPartyCreated(
                    parties.createEntityParty(entityPartyRequest(resolved.id, resolved.extract!!)),
                    now,
                )
        }
        log.infof(
            "case %s review resolved by %s: requiredSignatures=%d",
            cmd.caseId,
            cmd.operator,
            cmd.requiredSignatures,
        )
        return cases.update(resolved, KybEvents.registryVerified(resolved, now)).also(::armTimers)
    }

    override suspend fun reject(cmd: RejectCaseCommand): BusinessOnboardingCase {
        val case = get(cmd.caseId)
        val now = Instant.now(clock)
        val rejected = case.rejected(cmd.reason, now)
        return cases.update(rejected, KybEvents.rejected(rejected, now, cmd.operator)).also(::armTimers)
    }

    override suspend fun entityPartyActivated(entityPartyId: UUID) {
        val case = cases.findByEntityPartyId(entityPartyId) ?: return
        if (case.status.isTerminal) return
        val now = Instant.now(clock)
        val activated = case.entityPartyActivated(now)
        val event = if (activated.status == CaseStatus.ACTIVE) KybEvents.completed(activated, now) else null
        cases.update(activated, event).also(::armTimers)
    }

    override suspend fun abandonIfInState(caseId: UUID, expectedState: String, actor: String): Boolean {
        val case = cases.findById(caseId) ?: return false
        if (case.status.name != expectedState || case.status.isTerminal) return false
        val now = Instant.now(clock)
        val abandoned = case.abandoned(now)
        cases.update(abandoned, KybEvents.abandoned(abandoned, now, actor))
        log.infof("kyb case %s abandoned by %s after idling in %s", caseId, actor, expectedState)
        return true
    }

    /**
     * Arms the durable timers for the state just persisted. Never throws: the case row is already
     * committed and correct, and a Temporal hiccup must not turn a successful customer step into a
     * 500 — the miss is logged and counted instead.
     */
    private fun armTimers(case: BusinessOnboardingCase) {
        try {
            timers.stateEntered(case.id, case.status)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warnf(e, "could not arm timers for kyb case %s in %s", case.id, case.status)
            metrics.timerArmingFailed(case.status.name)
        }
    }

    private suspend fun ownedBy(caseId: UUID, callerPartyId: UUID): BusinessOnboardingCase {
        val case = get(caseId)
        if (case.initiatorPartyId != callerPartyId) throw CaseCallerMismatchException("only the initiator may do this")
        return case
    }

    /**
     * Every signer who actually signed becomes a mandate holder on the entity (ADR-0284 D3). The
     * role is a fact from the register (`REGISTRY`), or `OWNER` for a sole trader who IS the entity.
     */
    private suspend fun grantMandates(case: BusinessOnboardingCase) {
        val entity = requireNotNull(case.entityPartyId) { "a SIGNED case must carry its entity party" }
        val sole = case.extract?.isSoleTrader == true
        val joint = (case.requiredSignatures ?: 1) > 1
        case.signers.filter { it.status == SignerStatus.SIGNED && it.partyId != null }.forEach { signer ->
            parties.grantMandate(
                MandateRequest(
                    principalPartyId = entity,
                    agentPartyId = signer.partyId!!,
                    role = if (sole) "OWNER" else "LEGAL_REPRESENTATIVE",
                    authority = if (joint) "JOINT" else "SOLE",
                    source = if (signer.representativeIndex != null) "REGISTRY" else "POWER_OF_ATTORNEY",
                    evidenceRef = "kyb-case:${case.id}:signature:${signer.signatureRef}",
                ),
            )
        }
    }

    private fun entityPartyRequest(caseId: UUID, extract: RegistryExtract) = EntityPartyRequest(
        partyType = if (extract.legalFormClass == LegalFormClass.SOLE_TRADER) "SOLE_TRADER" else "COMPANY",
        legalName = extract.legalName,
        registrationNumber = extract.identifier.value,
        registrationCountry = extract.identifier.country ?: extract.registeredAddress?.countryCode,
        legalForm = extract.legalFormCode,
        taxId = extract.taxId,
        addressLine1 = extract.registeredAddress?.line1,
        city = extract.registeredAddress?.city,
        postalCode = extract.registeredAddress?.postalCode,
        countryCode = extract.registeredAddress?.countryCode,
        idempotencyKey = caseId.toString(),
    )
}
