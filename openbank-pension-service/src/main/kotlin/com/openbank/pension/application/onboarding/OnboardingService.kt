// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.onboarding

import com.openbank.pension.application.ProviderBoundary
import com.openbank.pension.application.port.out.ActivationOutcome
import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.application.port.out.ReassessmentRequiredException
import com.openbank.pension.application.port.out.StrategyApproval
import com.openbank.pension.application.port.out.StrategyNotPermittedException
import com.openbank.pension.application.port.out.StrategySuitabilityRequest
import com.openbank.pension.application.port.out.StrategyWarningsRequiredException
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.ApplicantFacts
import com.openbank.pension.domain.onboarding.CedingContract
import com.openbank.pension.domain.onboarding.IssuedKid
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.OnboardingRules
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.StrategyRecommendation
import com.openbank.pension.domain.onboarding.StrategyRecommender
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.pack.PackEvaluator
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.questionnaire.QuestionSetRegistry
import com.openbank.pension.domain.questionnaire.ReassessmentPolicy
import com.openbank.pension.domain.questionnaire.RefreshReason
import com.openbank.pension.domain.questionnaire.WarningAcknowledgement
import com.openbank.pension.domain.questionnaire.WarningCode
import com.openbank.pension.domain.questionnaire.WarningPolicy
import com.openbank.pension.domain.transfer.Counterparty
import com.openbank.pension.domain.transfer.TransferDirection
import com.openbank.pension.domain.transfer.TransferOrigin
import com.openbank.pension.domain.transfer.TransferRequest
import com.openbank.pension.domain.transfer.TransferStatus
import com.openbank.pension.domain.transfer.TransferTerms
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [actingPartyId] is ALWAYS the authenticated principal's party. [onBehalfOfPartyId] is set only
 * when a guardian applies for a ward; it is honoured only after party-service verifies the relation.
 */
data class StartOnboardingCommand(
    val actingPartyId: UUID,
    val onBehalfOfPartyId: UUID?,
    val kind: OnboardingKind,
    val productLine: ProductLine,
    val jurisdiction: String,
    val providerEntityId: UUID,
    val providerType: ProviderType,
    val schedule: ContributionSchedule,
    val declaredBirthDate: LocalDate,
    val declaredResidencyCountry: String?,
    val residencyEvidence: Set<String>,
    val ceding: CedingContract?,
)

data class ChooseStrategyCommand(val strategyCode: String?, val acknowledgeUnsuitable: Boolean, val language: String?)

class SignatureRejectedException(message: String) : RuntimeException(message)

/** Runs a block in ONE database transaction; nested repository transactions join it. */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/**
 * The client side of digital onboarding (ADR-0334 §4 steps 2-3) plus the callbacks its workflows
 * use. Plain Kotlin; CDI wiring lives in infrastructure.
 *
 * Every participant-facing method takes the caller's party id and answers "not found" for an
 * application of another party — a foreign id must read as absent, never as someone else's data.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class OnboardingService(
    private val applications: OnboardingApplicationRepository,
    private val assessments: SuitabilityAssessmentRepository,
    private val transfers: TransferRequestRepository,
    private val contracts: PensionContractRepository,
    private val packs: JurisdictionPackRegistry,
    private val rules: OnboardingRulesRegistry,
    private val kyc: PartyKycPort,
    private val relations: PartyRelationPort,
    private val documents: KeyInformationDocumentPort,
    private val signatures: SignatureVerificationPort,
    private val orchestrator: PensionOrchestrator,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val questionSets: QuestionSetRegistry,
    private val providerBoundary: ProviderBoundary,
) {

    private fun today(): LocalDate = LocalDate.now(clock)
    private fun now(): Instant = clock.instant()

    // --- client flow ---------------------------------------------------------------------------

    suspend fun start(command: StartOnboardingCommand): OnboardingApplication {
        providerBoundary.requireProvider(command.providerEntityId)
        val pack = packs.resolve(command.jurisdiction, command.productLine, today())
        val onboarding = rules.rules(pack.jurisdiction, pack.productLine, pack.version)
        require(command.providerType in pack.permittedProviderTypes) {
            "provider type ${command.providerType} may not provide ${pack.productLine} under this pack"
        }
        require(command.schedule.currency == pack.currency) { "contribution currency must be ${pack.currency}" }
        if (command.kind == OnboardingKind.TRANSFER_IN) {
            require(pack.transfer.allowed) { "this pack does not allow a transfer-in" }
        }
        val ward = command.onBehalfOfPartyId?.takeIf { it != command.actingPartyId }
        require(ward == null || relations.isLegalGuardian(command.actingPartyId, ward)) {
            "the caller is not a verified legal guardian of the applicant"
        }
        val applicantId = ward ?: command.actingPartyId
        val guardianId = if (ward != null) command.actingPartyId else null
        val profile = kyc.profile(applicantId)
            ?: throw IllegalArgumentException("party $applicantId is not known to KYC")
        val facts = ApplicantFacts(
            birthDate = profile.verifiedBirthDate ?: command.declaredBirthDate,
            residencyCountry = profile.verifiedResidencyCountry ?: command.declaredResidencyCountry,
            residencyEvidence = command.residencyEvidence,
            fullLegalCapacity = profile.fullLegalCapacity,
            guardianPartyId = guardianId,
        )
        val reasons = ineligibility(pack, onboarding, profile, facts, command.declaredBirthDate)
        val application = OnboardingApplication.start(
            partyId = applicantId,
            kind = command.kind,
            productLine = pack.productLine,
            jurisdiction = pack.jurisdiction,
            packVersion = pack.version,
            providerEntityId = command.providerEntityId,
            providerType = command.providerType,
            schedule = command.schedule,
            applicant = facts,
            ceding = command.ceding,
            ineligibilityReasons = reasons,
            expiresOn = today().plusDays(onboarding.applicationExpiryDays.toLong()),
            now = now(),
        )
        return applications.save(application)
    }

    /** Eligibility per pack (age, residency, capacity, guardian) plus the KYC verdict. */
    private fun ineligibility(
        pack: com.openbank.pension.domain.pack.JurisdictionPack,
        onboarding: com.openbank.pension.domain.onboarding.OnboardingRules,
        profile: KycProfile,
        facts: ApplicantFacts,
        declaredBirthDate: LocalDate,
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (profile.status != KycStatus.VERIFIED) reasons += "KYC is ${profile.status}, not VERIFIED"
        if (profile.verifiedBirthDate != null && profile.verifiedBirthDate != declaredBirthDate) {
            reasons += "declared birth date does not match the verified one"
        }
        reasons += PackEvaluator.checkEligibility(
            pack,
            facts.birthDate,
            facts.residencyCountry,
            facts.residencyEvidence,
            facts.guardianPartyId != null,
            today(),
        ).reasons
        if (!facts.fullLegalCapacity &&
            onboarding.guardianRequiredForLimitedCapacity &&
            facts.guardianPartyId == null
        ) {
            reasons += "a participant without full legal capacity needs a guardian"
        }
        return reasons
    }

    suspend fun get(id: UUID, partyId: UUID?): OnboardingApplication {
        val application = applications.findById(id) ?: throw OnboardingNotFoundException("onboarding application", id)
        if (partyId != null &&
            !application.actableBy(partyId)
        ) {
            throw OnboardingNotFoundException("onboarding application", id)
        }
        return application
    }

    suspend fun submitQuestionnaire(
        id: UUID,
        partyId: UUID,
        answers: QuestionnaireAnswers,
    ): Pair<OnboardingApplication, StrategyRecommendation> = submitAssessment(id, partyId) { application, onboarding ->
        SuitabilityAssessment.assess(
            partyId,
            id,
            application.productLine,
            onboarding.questionnaire,
            answers,
            today(),
            now(),
        )
    }

    /**
     * Records a new assessment built by [build] (legacy scales or the data-driven question set),
     * superseding the previous one in the same transaction — the superseded one stays for audit.
     */
    suspend fun submitAssessment(
        id: UUID,
        partyId: UUID,
        build: (OnboardingApplication, OnboardingRules) -> SuitabilityAssessment,
    ): Pair<OnboardingApplication, StrategyRecommendation> {
        val application = live(id, partyId)
        val assessment = build(application, rulesFor(application))
        val recommendation = recommend(application, assessment)
        val saved = tx.inTransaction {
            application.assessmentId?.let { previous ->
                assessments.findById(previous)?.let { assessments.save(it.supersede()) }
            }
            assessments.save(assessment)
            applications.save(application.submitQuestionnaire(assessment.id, recommendation.recommended, now()))
        }
        return saved to recommendation
    }

    /**
     * Re-assessment of an ACTIVATED application's contract: the same scoring as onboarding, but the
     * lifecycle stays ACTIVATED. Superseded assessments are kept for audit.
     */
    suspend fun reassess(
        id: UUID,
        partyId: UUID,
        build: (OnboardingApplication, OnboardingRules) -> SuitabilityAssessment,
    ): Pair<OnboardingApplication, StrategyRecommendation> {
        val application = get(id, partyId)
        val assessment = build(application, rulesFor(application))
        val recommendation = recommend(application, assessment)
        val saved = tx.inTransaction {
            application.assessmentId?.let { previous ->
                assessments.findById(previous)?.let { assessments.save(it.supersede()) }
            }
            assessments.save(assessment)
            applications.save(application.recordReassessment(assessment.id, recommendation.recommended, now()))
        }
        return saved to recommendation
    }

    /**
     * The suitability gate for ANY strategy a contract is to hold (StrategySuitabilityPort):
     * - no onboarding application on record (an S1 draft): no assessment exists, so only the pack's
     *   most conservative strategy is admissible; anything else is 403 — complete onboarding;
     * - the assessment in force must be current (expired / superseded / missing -> 409 re-assess);
     * - every warning WarningPolicy requires must be overridable under the regime (MiFID: a strategy
     *   above the profile is refused outright, 403) and acknowledged on this request (409 otherwise);
     *   the acknowledgement is bound to the hash of the exact wording shown.
     */
    suspend fun authorizeStrategy(request: StrategySuitabilityRequest): StrategyApproval {
        val onboarding = rules.rules(request.jurisdiction, request.productLine, request.packVersion)
        val option = requireNotNull(onboarding.strategy(request.strategyCode)) {
            "strategy ${request.strategyCode} is not offered under this pack"
        }
        val application = request.contractId?.let { applications.findByContract(it) }
            ?: return conservativeOnly(onboarding, option.riskClass, request)
        val assessment = assessmentInForce(application)
        val required = WarningPolicy.required(request.strategyCode, assessment, recommend(application, assessment))
        requireAllowedAndAcknowledged(onboarding, required, request)
        return StrategyApproval(
            application.id,
            warningAcks(application, required, assessment.id, request.strategyCode, request.language),
        )
    }

    /** No assessment on record (an S1 draft): only the pack's most conservative strategy. */
    private fun conservativeOnly(
        onboarding: OnboardingRules,
        riskClass: Int,
        request: StrategySuitabilityRequest,
    ): StrategyApproval {
        if (riskClass > onboarding.strategies.minOf { it.riskClass } || request.acknowledged.isNotEmpty()) {
            throw StrategyNotPermittedException(
                "without a suitability assessment only the most conservative strategy may be held; " +
                    "complete the onboarding questionnaire first",
            )
        }
        return StrategyApproval(null, emptyList())
    }

    /** The current assessment of [application], or 409 naming why it must be renewed. */
    private suspend fun assessmentInForce(application: OnboardingApplication): SuitabilityAssessment {
        val assessment = application.assessmentId?.let { assessments.findById(it) }
            ?: throw ReassessmentRequiredException(application.id, RefreshReason.NO_ASSESSMENT)
        val stale = ReassessmentPolicy.refreshReason(assessment, today())
        if (stale != null) throw ReassessmentRequiredException(application.id, stale)
        return assessment
    }

    /** MiFID-forbidden warnings refuse (403); every other required warning must be acknowledged (409). */
    private fun requireAllowedAndAcknowledged(
        onboarding: OnboardingRules,
        required: Set<WarningCode>,
        request: StrategySuitabilityRequest,
    ) {
        val forbidden = required.firstOrNull { !WarningPolicy.overridable(it, onboarding) }
        if (forbidden != null) {
            throw StrategyNotPermittedException(
                "strategy ${request.strategyCode} is not suitable under the " +
                    "${onboarding.questionnaire.regime} regime ($forbidden)",
            )
        }
        require(required.containsAll(request.acknowledged)) {
            "warnings ${(request.acknowledged - required).joinToString()} do not apply to ${request.strategyCode}"
        }
        val missing = required - request.acknowledged
        if (missing.isNotEmpty()) throw StrategyWarningsRequiredException(missing)
    }

    /** Stores an authorized change's acknowledgements on the application (audit). */
    suspend fun recordStrategyApproval(approval: StrategyApproval) {
        val id = approval.applicationId ?: return
        if (approval.acknowledgements.isEmpty()) return
        val application = applications.findById(id) ?: return
        applications.save(application.recordPostActivationAcknowledgements(approval.acknowledgements, now()))
    }

    /** The assessment in force for one of the caller's applications, or null before the first answer. */
    suspend fun assessmentOf(id: UUID, partyId: UUID): SuitabilityAssessment? =
        get(id, partyId).assessmentId?.let { assessments.findById(it) }

    /** Whether the application's pinned pack offers [strategyCode] at all. */
    fun offers(application: OnboardingApplication, strategyCode: String): Boolean =
        rulesFor(application).strategy(strategyCode) != null

    /** Saves partial answers (save-and-resume); they are validated by the caller against the set. */
    suspend fun saveDraft(id: UUID, partyId: UUID, answers: Map<String, String>): OnboardingApplication =
        applications.save(live(id, partyId).saveDraft(answers, now()))

    /**
     * Acknowledges warnings for a strategy BEFORE choosing it, so the UI can show each warning as
     * its own screen. Only warnings the choice actually requires, and only overridable ones, can be
     * acknowledged — an acknowledgement can never unlock a choice the regime forbids.
     */
    suspend fun acknowledgeWarnings(
        id: UUID,
        partyId: UUID,
        strategyCode: String,
        codes: Set<WarningCode>,
        language: String?,
    ): OnboardingApplication {
        val application = live(id, partyId)
        val onboarding = rulesFor(application)
        requireNotNull(onboarding.strategy(strategyCode)) { "strategy $strategyCode is not offered under this pack" }
        val assessment = currentAssessment(application)
        val required = WarningPolicy.required(strategyCode, assessment, recommend(application, assessment))
        require(codes.isNotEmpty()) { "at least one warning code is required" }
        require(required.containsAll(codes)) {
            "warnings ${(codes - required).joinToString()} do not apply to $strategyCode"
        }
        codes.forEach { code ->
            require(WarningPolicy.overridable(code, onboarding)) {
                "warning $code cannot be overridden under the ${onboarding.questionnaire.regime} regime"
            }
        }
        val acks = warningAcks(application, codes, assessment.id, strategyCode, language)
        return applications.save(application.acknowledge(acks, now()))
    }

    suspend fun recommendation(id: UUID, partyId: UUID): StrategyRecommendation {
        val application = get(id, partyId)
        return recommend(application, currentAssessment(application))
    }

    /**
     * Fixes the strategy and issues the key-information document for exactly it. Under MiFID a
     * strategy above the suitable class is refused; under a lighter regime the pack may allow it
     * with an acknowledged warning. An inappropriate product needs the same acknowledgement.
     */
    suspend fun chooseStrategy(id: UUID, partyId: UUID, command: ChooseStrategyCommand): OnboardingApplication {
        val application = live(id, partyId)
        val onboarding = rulesFor(application)
        val assessment = currentAssessment(application)
        val recommendation = recommend(application, assessment)
        val code = command.strategyCode ?: recommendation.recommended
        requireNotNull(onboarding.strategy(code)) { "strategy $code is not offered under this pack" }
        val required = WarningPolicy.required(code, assessment, recommendation)
        if (WarningCode.STRATEGY_ABOVE_PROFILE in required) {
            require(WarningPolicy.overridable(WarningCode.STRATEGY_ABOVE_PROFILE, onboarding)) {
                "strategy $code is above the suitable risk class ${recommendation.maxRiskClass}"
            }
        }
        // A warning is acknowledged either beforehand (POST .../warnings/acknowledge, which records
        // the exact wording) or together with the choice; never skipped (ZDPS § 136(3), MiFID 25(3)).
        var acknowledged = application
        val missing = WarningPolicy.missing(required, application.warningAcknowledgements, assessment.id, code)
        if (missing.isNotEmpty()) {
            require(command.acknowledgeUnsuitable) {
                "the choice needs acknowledged warnings: ${missing.joinToString()}"
            }
            val acks = warningAcks(application, missing, assessment.id, code, command.language)
            acknowledged = application.acknowledge(acks, now())
        }
        val document = documents.generate(
            KidRequest(
                applicationId = id,
                partyId = partyId,
                type = onboarding.keyInformationDocument.type,
                templateCode = onboarding.keyInformationDocument.templateCode,
                productLine = application.productLine,
                strategyCode = code,
                language = command.language,
            ),
        )
        val kid = IssuedKid(document.documentId, document.sha256, code, now())
        return applications.save(acknowledged.issueKid(code, required.isNotEmpty(), kid, now()))
    }

    suspend fun acceptKid(id: UUID, partyId: UUID, documentId: String): OnboardingApplication =
        applications.save(live(id, partyId).acceptKid(documentId, now()))

    /**
     * SCA-signs the contract (and, for a transfer-in, the transfer request) and hands over to the
     * workflow. Retrying a signature that already landed only re-ensures the workflow, which is
     * idempotent — so a lost response never strands a signed application without its workflow.
     */
    suspend fun sign(id: UUID, partyId: UUID, challengeId: String): OnboardingApplication {
        val application = get(id, partyId)
        if (application.status == OnboardingStatus.SIGNED && application.signatureRef == challengeId) {
            ensureOrchestration(application)
            return application
        }
        val live = live(id, partyId)
        check(live.status == OnboardingStatus.KID_ACCEPTED) { "the key-information document must be accepted first" }
        val kid = checkNotNull(live.kid)
        requireWarningsAcknowledged(live)
        // Bound to THIS application and THIS document; the signer is the acting party (the
        // guardian, for a ward). sca-service spends the challenge, so it cannot sign twice.
        val outcome = signatures.verify(partyId, challengeId, kid.sha256, "pension-onboarding:$id:${kid.documentId}")
        if (outcome != SignatureOutcome.VERIFIED) throw SignatureRejectedException("the SCA challenge was not verified")
        val pack = packs.pinned(live.jurisdiction, live.productLine, live.packVersion)
        val onboarding = rulesFor(live)
        val contract = PensionContract.draft(
            participantPartyId = partyId,
            productLine = live.productLine,
            jurisdiction = live.jurisdiction,
            packVersion = live.packVersion,
            providerEntityId = live.providerEntityId,
            providerType = live.providerType,
            participantBirthDate = live.applicant.birthDate,
            schedule = live.schedule,
            initialStrategy = checkNotNull(live.chosenStrategy),
            beneficiaries = emptyList(),
            today = today(),
            now = now(),
        ).submit(now())
        val transfer = live.ceding?.let { ceding ->
            TransferRequest.request(
                direction = TransferDirection.IN,
                origin = TransferOrigin.PARTICIPANT,
                contractId = contract.id,
                partyId = partyId,
                counterparty = Counterparty(ceding.providerId, ceding.providerName, ceding.contractNumber),
                currency = pack.currency,
                signatureRef = challengeId,
                deadline = TransferTerms.deadline(pack.transfer, today()),
                now = now(),
            )
        }
        val signed = tx.inTransaction {
            contracts.save(contract)
            transfer?.let { transfers.save(it) }
            applications.save(
                live.sign(
                    challengeId,
                    contract.id,
                    transfer?.id,
                    today().plusDays(onboarding.coolingOffDays.toLong()),
                    now(),
                ),
            )
        }
        ensureOrchestration(signed)
        return signed
    }

    /** Withdrawal in the cooling-off period: the contract closes and the workflow is told to stop. */
    suspend fun withdraw(id: UUID, partyId: UUID): OnboardingApplication {
        val application = get(id, partyId)
        if (application.transferRequestId != null) {
            val transfer = transfers.findById(application.transferRequestId)
            check(transfer == null || transfer.status !in FUNDED) {
                "the transferred funds have arrived; withdrawal now needs a transfer back (not yet supported)"
            }
        }
        val withdrawn = tx.inTransaction {
            closeContract(application.contractId)
            applications.save(application.withdraw(today(), now()))
        }
        if (application.transferRequestId != null) {
            orchestrator.signalTransferWithdrawal(application.transferRequestId)
        } else {
            orchestrator.signalWithdrawal(id)
        }
        return withdrawn
    }

    suspend fun abandon(id: UUID, partyId: UUID): OnboardingApplication =
        applications.save(get(id, partyId).abandon(now()))

    // --- operator / integration --------------------------------------------------------------

    suspend fun list(status: OnboardingStatus?, limit: Int): List<OnboardingApplication> =
        applications.findByStatus(status, limit)

    /** The first contribution reached the contract (until slice S3 emits it as an event). */
    suspend fun contributionReceived(id: UUID): OnboardingApplication {
        val application = get(id, null)
        check(application.status == OnboardingStatus.SIGNED) {
            "a ${application.status} application awaits no contribution"
        }
        check(application.kind == OnboardingKind.NEW_CONTRACT) { "a transfer-in activates on the transferred funds" }
        orchestrator.signalContributionReceived(id)
        return application
    }

    /** [com.openbank.pension.application.port.out.OnboardingActivationPort], for slice S3. */
    suspend fun firstContributionReceived(contractId: UUID): ActivationOutcome {
        val application = awaiting(contractId) ?: return ActivationOutcome.NOT_AWAITING
        orchestrator.signalContributionReceived(application.id)
        return ActivationOutcome.SIGNALLED
    }

    /** [com.openbank.pension.application.port.out.OnboardingActivationPort.awaitsFirstContribution]. */
    suspend fun awaitsFirstContribution(contractId: UUID): Boolean = awaiting(contractId) != null

    private suspend fun awaiting(contractId: UUID): OnboardingApplication? =
        applications.findByContract(contractId)?.takeIf {
            it.status == OnboardingStatus.SIGNED && it.kind == OnboardingKind.NEW_CONTRACT
        }

    // --- workflow callbacks (idempotent: a retried activity must not fail on its own effect) -----

    /** PENDING_ACTIVATION -> ACTIVE. Answers false when the application already left SIGNED. */
    suspend fun activate(id: UUID): Boolean {
        val application = get(id, null)
        if (application.status != OnboardingStatus.SIGNED) return application.status == OnboardingStatus.ACTIVATED
        // The aggregate guards first (signature, cooling-off); only then does the contract move.
        val activated = application.activate(today(), now())
        tx.inTransaction {
            val contractId = checkNotNull(application.contractId)
            val contract = contracts.findById(contractId) ?: throw OnboardingNotFoundException("contract", contractId)
            if (contract.status == ContractStatus.PENDING_ACTIVATION) {
                contracts.save(contract.activate(today(), now()))
            }
            applications.save(activated)
        }
        return true
    }

    suspend fun expire(id: UUID, reason: String) {
        val application = get(id, null)
        if (application.status.terminal || application.status == OnboardingStatus.ACTIVATED) return
        tx.inTransaction {
            closeContract(application.contractId)
            applications.save(application.expire(reason, now()))
        }
    }

    /** Closes a never-funded or withdrawn contract; ACTIVE needs TERMINATING first. */
    internal suspend fun closeContract(contractId: UUID?) {
        if (contractId == null) return
        val contract = contracts.findById(contractId) ?: return
        val closed = when (contract.status) {
            ContractStatus.DRAFT, ContractStatus.PENDING_ACTIVATION, ContractStatus.TERMINATING -> contract.close(now())
            ContractStatus.ACTIVE, ContractStatus.SUSPENDED -> contract.requestTermination(now()).close(now())
            else -> return
        }
        contracts.save(closed)
    }

    private companion object {
        val FUNDED = setOf(TransferStatus.FUNDS_RECEIVED, TransferStatus.COMPLETED)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private suspend fun ensureOrchestration(application: OnboardingApplication) {
        val onboarding = rulesFor(application)
        val transferId = application.transferRequestId
        if (transferId != null) {
            val pack = packs.pinned(application.jurisdiction, application.productLine, application.packVersion)
            orchestrator.startTransferIn(
                transferId,
                application.id,
                TransferInTimers(
                    responseDeadlineDays = requireNotNull(pack.transfer.deadlineDays) {
                        "pack has no transfer deadline"
                    },
                    fundsGraceDays = onboarding.transferIn.fundsGraceDays,
                    coolingOffDays = onboarding.coolingOffDays,
                ),
            )
        } else {
            orchestrator.startOnboarding(
                application.id,
                OnboardingTimers(
                    coolingOffDays = onboarding.coolingOffDays,
                    activationTrigger = onboarding.activation.trigger,
                    activationDeadlineDays = onboarding.activation.deadlineDays,
                ),
            )
        }
    }

    private suspend fun live(id: UUID, partyId: UUID): OnboardingApplication {
        val application = get(id, partyId)
        check(application.status.preSignature) { "a ${application.status} application can no longer be edited" }
        if (today().isAfter(application.expiresOn)) {
            applications.save(application.expire("application lapsed unsigned", now()))
            error("the application lapsed on ${application.expiresOn}")
        }
        return application
    }

    private suspend fun currentAssessment(application: OnboardingApplication): SuitabilityAssessment {
        val assessmentId = checkNotNull(application.assessmentId) { "the questionnaire has not been answered" }
        val assessment = assessments.findById(assessmentId)
            ?: throw OnboardingNotFoundException("suitability assessment", assessmentId)
        check(assessment.isValidOn(today())) { "the questionnaire answers expired; answer it again" }
        return assessment
    }

    private fun recommend(
        application: OnboardingApplication,
        assessment: SuitabilityAssessment,
    ): StrategyRecommendation {
        val pack = packs.pinned(application.jurisdiction, application.productLine, application.packVersion)
        return StrategyRecommender.recommend(
            rulesFor(application),
            assessment,
            application.applicant.birthDate,
            pack.payout.minAge,
            today(),
        )
    }

    /** Builds acknowledgements bound to the wording of the application's question set. */
    internal fun warningAcks(
        application: OnboardingApplication,
        codes: Set<WarningCode>,
        assessmentId: UUID,
        strategyCode: String,
        language: String?,
    ): List<WarningAcknowledgement> {
        val set = questionSets.questionSet(application.jurisdiction, application.productLine)
        val lang = if (language?.lowercase()?.startsWith("en") == true) "en" else "cs"
        return codes.map { code ->
            WarningAcknowledgement(
                code = code,
                assessmentId = assessmentId,
                strategyCode = strategyCode,
                textSha256 = WarningPolicy.sha256(set.warning(code).text.text(lang)),
                language = lang,
                acknowledgedAt = now(),
            )
        }
    }

    /**
     * Defence in depth at the signature: every warning the chosen strategy requires under the
     * CURRENT assessment must carry an acknowledgement, whatever path issued the document.
     */
    private suspend fun requireWarningsAcknowledged(application: OnboardingApplication) {
        val assessment = currentAssessment(application)
        val strategy = checkNotNull(application.chosenStrategy) { "no strategy has been chosen" }
        val required = WarningPolicy.required(strategy, assessment, recommend(application, assessment))
        val missing = WarningPolicy.missing(required, application.warningAcknowledgements, assessment.id, strategy)
        check(missing.isEmpty()) { "warnings must be acknowledged before signing: ${missing.joinToString()}" }
    }

    private fun rulesFor(application: OnboardingApplication) =
        rules.rules(application.jurisdiction, application.productLine, application.packVersion)
}
