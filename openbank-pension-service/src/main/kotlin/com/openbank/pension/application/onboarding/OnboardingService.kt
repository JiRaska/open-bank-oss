// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.onboarding

import com.openbank.pension.application.port.out.PensionContractRepository
import com.openbank.pension.domain.model.ContractStatus
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.PensionContract
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.ApplicantFacts
import com.openbank.pension.domain.onboarding.CedingContract
import com.openbank.pension.domain.onboarding.IssuedKid
import com.openbank.pension.domain.onboarding.OnboardingApplication
import com.openbank.pension.domain.onboarding.OnboardingKind
import com.openbank.pension.domain.onboarding.OnboardingStatus
import com.openbank.pension.domain.onboarding.QuestionnaireAnswers
import com.openbank.pension.domain.onboarding.StrategyRecommendation
import com.openbank.pension.domain.onboarding.StrategyRecommender
import com.openbank.pension.domain.onboarding.SuitabilityAssessment
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.pack.PackEvaluator
import com.openbank.pension.domain.pack.ProviderType
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

data class StartOnboardingCommand(
    val partyId: UUID,
    val kind: OnboardingKind,
    val productLine: ProductLine,
    val jurisdiction: String,
    val providerEntityId: UUID,
    val providerType: ProviderType,
    val schedule: ContributionSchedule,
    val declaredBirthDate: LocalDate,
    val declaredResidencyCountry: String?,
    val residencyEvidence: Set<String>,
    val guardianPartyId: UUID?,
    val ceding: CedingContract?,
)

data class ChooseStrategyCommand(
    val strategyCode: String?,
    val acknowledgeUnsuitable: Boolean,
    val language: String?,
)

class SignatureRejectedException(message: String) : RuntimeException(message)

/** Runs a block in ONE database transaction; nested repository transactions join it. */
fun interface TransactionRunner {
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
    private val documents: KeyInformationDocumentPort,
    private val signatures: SignatureVerificationPort,
    private val orchestrator: PensionOrchestrator,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    private fun today(): LocalDate = LocalDate.now(clock)
    private fun now(): Instant = clock.instant()

    // --- client flow ---------------------------------------------------------------------------

    suspend fun start(command: StartOnboardingCommand): OnboardingApplication {
        val pack = packs.resolve(command.jurisdiction, command.productLine, today())
        val onboarding = rules.rules(pack.jurisdiction, pack.productLine, pack.version)
        require(command.providerType in pack.permittedProviderTypes) {
            "provider type ${command.providerType} may not provide ${pack.productLine} under this pack"
        }
        require(command.schedule.currency == pack.currency) { "contribution currency must be ${pack.currency}" }
        if (command.kind == OnboardingKind.TRANSFER_IN) {
            require(pack.transfer.allowed) { "this pack does not allow a transfer-in" }
        }
        val profile = kyc.profile(command.partyId)
            ?: throw IllegalArgumentException("party ${command.partyId} is not known to KYC")
        val facts = ApplicantFacts(
            birthDate = profile.verifiedBirthDate ?: command.declaredBirthDate,
            residencyCountry = profile.verifiedResidencyCountry ?: command.declaredResidencyCountry,
            residencyEvidence = command.residencyEvidence,
            fullLegalCapacity = profile.fullLegalCapacity,
            guardianPartyId = command.guardianPartyId,
        )
        val reasons = mutableListOf<String>()
        if (profile.status != KycStatus.VERIFIED) reasons += "KYC is ${profile.status}, not VERIFIED"
        if (profile.verifiedBirthDate != null && profile.verifiedBirthDate != command.declaredBirthDate) {
            reasons += "declared birth date does not match the verified one"
        }
        val eligibility = PackEvaluator.checkEligibility(
            pack, facts.birthDate, facts.residencyCountry, facts.residencyEvidence, facts.guardianPartyId != null, today(),
        )
        reasons += eligibility.reasons
        if (!facts.fullLegalCapacity && onboarding.guardianRequiredForLimitedCapacity && facts.guardianPartyId == null) {
            reasons += "a participant without full legal capacity needs a guardian"
        }
        val application = OnboardingApplication.start(
            partyId = command.partyId,
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

    suspend fun get(id: UUID, partyId: UUID?): OnboardingApplication {
        val application = applications.findById(id) ?: throw OnboardingNotFoundException("onboarding application", id)
        if (partyId != null && application.partyId != partyId) throw OnboardingNotFoundException("onboarding application", id)
        return application
    }

    suspend fun submitQuestionnaire(
        id: UUID,
        partyId: UUID,
        answers: QuestionnaireAnswers,
    ): Pair<OnboardingApplication, StrategyRecommendation> {
        val application = live(id, partyId)
        val onboarding = rulesFor(application)
        val assessment = SuitabilityAssessment.assess(
            partyId, id, application.productLine, onboarding.questionnaire, answers, today(), now(),
        )
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
        val option = requireNotNull(onboarding.strategy(code)) { "strategy $code is not offered under this pack" }
        val unsuitable = option.code !in recommendation.suitable && option.code != recommendation.recommended
        if (unsuitable) {
            require(onboarding.questionnaire.allowUnsuitableWithWarning) {
                "strategy $code is above the suitable risk class ${recommendation.maxRiskClass}"
            }
            require(command.acknowledgeUnsuitable) { "choosing an unsuitable strategy needs an acknowledged warning" }
        }
        if (assessment.appropriate == false) {
            require(command.acknowledgeUnsuitable) { "the product is not appropriate; the warning must be acknowledged" }
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
        return applications.save(application.issueKid(code, unsuitable || assessment.appropriate == false, kid, now()))
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
        val outcome = signatures.verify(partyId, challengeId, kid.sha256, "pension-onboarding:$id")
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
        check(application.status == OnboardingStatus.SIGNED) { "a ${application.status} application awaits no contribution" }
        check(application.kind == OnboardingKind.NEW_CONTRACT) { "a transfer-in activates on the transferred funds" }
        orchestrator.signalContributionReceived(id)
        return application
    }

    // --- workflow callbacks (idempotent: a retried activity must not fail on its own effect) -----

    /** PENDING_ACTIVATION -> ACTIVE. Answers false when the application already left SIGNED. */
    suspend fun activate(id: UUID): Boolean {
        val application = get(id, null)
        if (application.status != OnboardingStatus.SIGNED) return application.status == OnboardingStatus.ACTIVATED
        tx.inTransaction {
            val contractId = checkNotNull(application.contractId)
            val contract = contracts.findById(contractId) ?: throw OnboardingNotFoundException("contract", contractId)
            if (contract.status == ContractStatus.PENDING_ACTIVATION) {
                contracts.save(contract.activate(today(), now()))
            }
            applications.save(application.activate(now()))
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
                    responseDeadlineDays = requireNotNull(pack.transfer.deadlineDays) { "pack has no transfer deadline" },
                    fundsGraceDays = onboarding.transferIn.fundsGraceDays,
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
            throw IllegalStateException("the application lapsed on ${application.expiresOn}")
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

    private fun recommend(application: OnboardingApplication, assessment: SuitabilityAssessment): StrategyRecommendation {
        val pack = packs.pinned(application.jurisdiction, application.productLine, application.packVersion)
        return StrategyRecommender.recommend(
            rulesFor(application), assessment, application.applicant.birthDate, pack.payout.minAge, today(),
        )
    }

    private fun rulesFor(application: OnboardingApplication) =
        rules.rules(application.jurisdiction, application.productLine, application.packVersion)
}
