// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.`in`.AcceptDisclosuresCommand
import com.openbank.kyb.application.port.`in`.AnswerQuestionnaireCommand
import com.openbank.kyb.application.port.`in`.BusinessOnboardingUseCase
import com.openbank.kyb.application.port.`in`.ClaimInvitationCommand
import com.openbank.kyb.application.port.`in`.InviteCosignersCommand
import com.openbank.kyb.application.port.`in`.LookupCommand
import com.openbank.kyb.application.port.`in`.MakeDeclarationsCommand
import com.openbank.kyb.application.port.`in`.MatchInitiatorCommand
import com.openbank.kyb.application.port.`in`.PrepareAgreementCommand
import com.openbank.kyb.application.port.`in`.QuestionnairePrefill
import com.openbank.kyb.application.port.`in`.RejectCaseCommand
import com.openbank.kyb.application.port.`in`.ResolveReviewCommand
import com.openbank.kyb.application.port.`in`.SignCommand
import com.openbank.kyb.application.port.`in`.StartCaseCommand
import com.openbank.kyb.application.port.out.AgreementEntity
import com.openbank.kyb.application.port.out.AgreementParty
import com.openbank.kyb.application.port.out.BeneficialOwnershipPort
import com.openbank.kyb.application.port.out.BusinessAgreementRequest
import com.openbank.kyb.application.port.out.BusinessAgreementView
import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.BusinessOnboardingSettings
import com.openbank.kyb.application.port.out.BusinessOnboardingWorkflowPort
import com.openbank.kyb.application.port.out.CeremonySignerStatus
import com.openbank.kyb.application.port.out.DocumentGateway
import com.openbank.kyb.application.port.out.EntityPartyRequest
import com.openbank.kyb.application.port.out.InvitationTokens
import com.openbank.kyb.application.port.out.KybMetricsPort
import com.openbank.kyb.application.port.out.MandateRequest
import com.openbank.kyb.application.port.out.PartyGateway
import com.openbank.kyb.domain.czech.CzechRepresentationRuleParser
import com.openbank.kyb.domain.model.AcceptedDisclosure
import com.openbank.kyb.domain.model.AgreementConflictException
import com.openbank.kyb.domain.model.AgreementRecord
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.InitiatorIdentityMismatchException
import com.openbank.kyb.domain.model.KnownPerson
import com.openbank.kyb.domain.model.KybEvents
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.libs.domain.identifiers.Ids
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

    @Inject lateinit var representation: RepresentationAttestationService

    @Inject lateinit var clock: Clock

    @Inject lateinit var documents: DocumentGateway

    @Inject lateinit var settings: BusinessOnboardingSettings

    @Inject lateinit var ubo: BeneficialOwnershipPort

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
        val started = BusinessOnboardingCase.start(Ids.newId(), identifier, cmd.initiatorPartyId, now)
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
        val verified = started.registryVerified(extract, representation.decide(extract), now)
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
        // Who the initiator IS comes from party-service's record, never from the request: `claimedName`
        // and `dateOfBirth` stay on the command for the API contract and are not used for identity.
        val identity = parties.initiatorIdentity(case.initiatorPartyId)
            ?: throw InitiatorIdentityMismatchException("no identity on record for the initiator")
        val matched = case.initiatorMatched(cmd.representativeIndex, identity, now)
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

    override suspend fun answerQuestionnaire(cmd: AnswerQuestionnaireCommand): BusinessOnboardingCase {
        val case = participantOf(cmd.caseId, cmd.callerPartyId)
        val now = Instant.now(clock)
        return cases.update(case.questionnaireAnswered(cmd.questionnaire, cmd.callerPartyId, now), null)
            .also(::armTimers)
    }

    override suspend fun makeDeclarations(cmd: MakeDeclarationsCommand): BusinessOnboardingCase {
        val case = participantOf(cmd.caseId, cmd.callerPartyId)
        val now = Instant.now(clock)
        // The people a PEP entry must cover: every natural-person beneficial owner the register
        // reports (a corporate owner is not a person and has no PEP status of its own), plus every
        // listed representative, which the aggregate adds from the extract.
        val uboNames = ubo.lookup(case.identifier).reportableOwners.filter { !it.corporate }.map { it.fullName }
        val made = case.declarationsMade(cmd.declarations, uboNames, knownPersons(case), cmd.callerPartyId, now)
        return cases.update(made, null).also(::armTimers)
    }

    override suspend fun questionnairePrefill(caseId: UUID, callerPartyId: UUID): QuestionnairePrefill {
        val case = participantOf(caseId, callerPartyId)
        val previous = cases.findInvolving(callerPartyId)
            .filter { it.id != caseId }
            .mapNotNull { it.questionnaire }
            .filter { it.answeredBy == callerPartyId }
            .maxByOrNull { it.answeredAt ?: Instant.EPOCH }
        return QuestionnairePrefill(knownPersons(case), previous)
    }

    /**
     * Everyone on the case the bank already knows as a customer, with the PEP fact their profile
     * carries. A profile lookup that finds nothing leaves the fact unknown — never "not a PEP".
     */
    private suspend fun knownPersons(case: BusinessOnboardingCase): List<KnownPerson> =
        case.signers.filter { it.partyId != null }.map { s ->
            val profile = parties.pepProfile(s.partyId!!)
            KnownPerson(s.fullName, s.partyId, profile?.pep, profile?.category)
        }

    override suspend fun prepareAgreement(cmd: PrepareAgreementCommand): BusinessAgreementView {
        require(cmd.lang in AGREEMENT_LANGS) { "lang must be one of ${AGREEMENT_LANGS.joinToString(", ")}" }
        val case = participantOf(cmd.caseId, cmd.callerPartyId)
        case.requireAgreementPreparable()
        val view = documents.ensureBusinessAgreement(agreementRequest(case, cmd.lang))
        requireOwnCeremony(case, view)
        val now = Instant.now(clock)
        val prepared = case.agreementPrepared(
            AgreementRecord(
                documentId = view.documentId,
                ceremonyId = view.ceremonyId,
                templateCode = view.templateCode,
                templateVersion = view.templateVersion,
                sha256 = view.sha256,
                lang = cmd.lang,
            ),
            now,
        )
        cases.update(prepared, null).also(::armTimers)
        return view
    }

    override suspend fun acceptDisclosures(cmd: AcceptDisclosuresCommand): BusinessOnboardingCase {
        val case = participantOf(cmd.caseId, cmd.callerPartyId)
        val agreement = case.agreement ?: throw AgreementConflictException(
            AgreementConflictException.NOT_PREPARED,
            "the business agreement has not been prepared for this case",
        )
        // The set to match is the one document-service holds NOW, never one the client remembers.
        val view = currentCeremony(case, agreement.lang)
        if (view.documentId != agreement.documentId || view.ceremonyId != agreement.ceremonyId) {
            throw AgreementConflictException(
                AgreementConflictException.DISCLOSURES_STALE,
                "the agreement was re-rendered — prepare it again before accepting",
            )
        }
        val current = view.disclosures.map { AcceptedDisclosure(it.code, it.version, it.sha256) }
        val now = Instant.now(clock)
        return cases.update(case.disclosuresAccepted(cmd.disclosures, current, cmd.callerPartyId, now), null)
            .also(::armTimers)
    }

    override suspend fun sign(cmd: SignCommand): BusinessOnboardingCase {
        val case = get(cmd.caseId)
        // Local preconditions first (acceptance recorded, the case's own ceremony id), then the
        // authority: document-service must say this caller SIGNED that ceremony, for this case.
        case.requireSignable(cmd.signerPartyId, cmd.signatureRef)
        val agreement = requireNotNull(case.agreement)
        val view = currentCeremony(case, agreement.lang)
        if (view.ceremonyId != agreement.ceremonyId || view.documentId != agreement.documentId) {
            throw AgreementConflictException(
                AgreementConflictException.SIGNATURE_REF_MISMATCH,
                "the ceremony document-service holds for this case is not the one this case recorded",
            )
        }
        val ceremonySigner = view.signers.firstOrNull { it.partyRef == cmd.signerPartyId }
        if (ceremonySigner?.status != CeremonySignerStatus.SIGNED) {
            throw AgreementConflictException(
                AgreementConflictException.CEREMONY_NOT_SIGNED,
                "the signature ceremony does not record a completed signature by this party",
            )
        }
        val now = Instant.now(clock)
        val signed = case.signed(cmd.signerPartyId, cmd.signatureRef, now, settings.highRiskCountries)
        if ((signed.status == CaseStatus.SIGNED || signed.status == CaseStatus.ACTIVE) &&
            boundAttestationChanged(signed)
        ) {
            val reviewed = signed.representationRuleChanged(now)
            return cases.update(reviewed, KybEvents.reviewRequired(reviewed, now)).also(::armTimers)
        }
        val policy = if (signed.status == CaseStatus.SIGNED || signed.status == CaseStatus.ACTIVE) {
            signed.statutoryPolicyEvidence(now)
        } else {
            null
        }
        val event = when (signed.status) {
            CaseStatus.SIGNED, CaseStatus.ACTIVE ->
                KybEvents.agreementSigned(signed, now, cmd.signerPartyId.toString(), policy)
            CaseStatus.MANUAL_REVIEW -> KybEvents.reviewRequired(signed, now)
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
        val attestationId = matchingAttestationId(case, cmd.requiredSignatures, cmd.requiredSignerRoles)
        var resolved = case.reviewResolved(cmd.requiredSignatures, now, cmd.requiredSignerRoles, attestationId)
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
        // A review can now COMPLETE a case: `reviewResolved` finishes one whose signatures were
        // already collected and satisfy what the operator just confirmed (#9711). That path has to
        // do everything `sign` does on the same transition — grant the mandates and emit the
        // signed/completed events — or the entity goes active with NOBODY authorised to act for
        // it, which is silent: the case reads ACTIVE from every angle and every later request by
        // its own representatives is refused.
        if (resolved.status == CaseStatus.SIGNED || resolved.status == CaseStatus.ACTIVE) {
            grantMandates(resolved)
            val saved = cases.update(
                resolved,
                KybEvents.agreementSigned(resolved, now, cmd.operator, resolved.statutoryPolicyEvidence(now)),
            )
            if (saved.status == CaseStatus.ACTIVE) cases.update(saved, KybEvents.completed(saved, now))
            return saved.also(::armTimers)
        }
        return cases.update(resolved, KybEvents.registryVerified(resolved, now)).also(::armTimers)
    }

    private suspend fun boundAttestationChanged(case: BusinessOnboardingCase): Boolean {
        val bound = case.representationAttestationId ?: return false
        val extract = case.extract ?: return true
        val current = representation.decide(extract) as? RepresentationDecision.Attested ?: return true
        if (current.attestation.id != bound) return true
        if (current.attestation.confirmedSigners != case.requiredSignatures) return true
        return current.attestation.confirmedRoles != case.requiredSignerRoles
    }

    private suspend fun matchingAttestationId(case: BusinessOnboardingCase, signers: Int, roles: List<String>): UUID? {
        val extract = case.extract ?: return null
        if (extract.verification != ExtractVerification.VERIFIED || extract.status != EntityStatus.ACTIVE) return null
        val current = representation.decide(extract) as? RepresentationDecision.Attested ?: return null
        val normalizedRoles = roles.map(CzechRepresentationRuleParser::fold).filter(String::isNotBlank)
        return current.attestation.takeIf {
            it.confirmedSigners == signers && it.confirmedRoles == normalizedRoles
        }?.id
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

    /** The initiator or an identified signer; anybody else is refused (403). */
    private suspend fun participantOf(caseId: UUID, callerPartyId: UUID): BusinessOnboardingCase {
        val case = get(caseId)
        if (!case.isParticipant(callerPartyId)) {
            throw CaseCallerMismatchException("only the initiator or a signer of this case may do this")
        }
        return case
    }

    private suspend fun currentCeremony(case: BusinessOnboardingCase, lang: String): BusinessAgreementView {
        val view = documents.businessAgreement(case.id, lang) ?: throw AgreementConflictException(
            AgreementConflictException.CEREMONY_NOT_FOUND,
            "document-service holds no business agreement for this case",
        )
        requireOwnCeremony(case, view)
        return view
    }

    /** The ceremony's document must belong to THIS case — a ceremony id from another case is never accepted. */
    private fun requireOwnCeremony(case: BusinessOnboardingCase, view: BusinessAgreementView) {
        if (view.caseId != case.id) {
            throw AgreementConflictException(
                AgreementConflictException.CEREMONY_CASE_MISMATCH,
                "the agreement document belongs to a different case",
            )
        }
    }

    private fun agreementRequest(case: BusinessOnboardingCase, lang: String): BusinessAgreementRequest {
        val ex = requireNotNull(case.extract) { "a case ready to sign carries its register extract" }
        val entityParty = requireNotNull(case.entityPartyId) { "a case ready to sign carries its entity party" }
        val country = ex.identifier.country ?: ex.registeredAddress?.countryCode
        val seat = ex.registeredAddress?.let { a ->
            listOfNotNull(
                a.line1,
                listOfNotNull(a.postalCode, a.city).joinToString(" ").takeIf { it.isNotBlank() },
                a.countryCode,
            ).joinToString(", ")
        }.orEmpty()
        val signerByIndex = case.signers.filter {
            it.representativeIndex != null
        }.associateBy { it.representativeIndex }
        return BusinessAgreementRequest(
            caseId = case.id,
            entityPartyId = entityParty,
            lang = lang,
            entity = AgreementEntity(
                name = ex.legalName,
                ico = ex.identifier.value,
                seat = seat,
                legalForm = settings.legalFormLabel(country, ex.legalFormCode, lang)
                    ?: ex.legalFormCode
                    ?: ex.legalFormClass.name,
            ),
            representatives = ex.representatives.mapIndexed { i, r ->
                AgreementParty(signerByIndex[i]?.partyId, r.fullName, r.role)
            },
            signers = case.signers
                .filter {
                    it.partyId != null && it.status != SignerStatus.INVITED && it.status != SignerStatus.DECLINED
                }
                .map { s ->
                    AgreementParty(
                        s.partyId,
                        s.fullName,
                        s.representativeIndex?.let { ex.representatives.getOrNull(it)?.role },
                    )
                },
            signingRule = ex.representationRule.sourceText
                ?: "${ex.representationRule.mode} (${case.requiredSignatures} signature(s))",
            product = settings.businessProduct,
        )
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
        val requiredSignatures = requireNotNull(
            case.requiredSignatures,
        ) {
            "a SIGNED case must carry its signature quorum"
        }
        case.signers.filter { it.status == SignerStatus.SIGNED && it.partyId != null }.forEach { signer ->
            parties.grantMandate(
                MandateRequest(
                    principalPartyId = entity,
                    agentPartyId = signer.partyId!!,
                    role = if (sole) "OWNER" else "LEGAL_REPRESENTATIVE",
                    authority = if (joint) "JOINT" else "SOLE",
                    requiredSignatures = requiredSignatures,
                    source = if (signer.representativeIndex != null) "REGISTRY" else "POWER_OF_ATTORNEY",
                    evidenceRef = "kyb-case:${case.id}:signature:${signer.signatureRef}",
                ),
            )
        }
    }

    private companion object {
        val AGREEMENT_LANGS = setOf("cs", "en")
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
