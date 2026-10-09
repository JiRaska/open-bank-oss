// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.onboarding

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.pension.domain.model.ContributionSchedule
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.pack.ProviderType
import com.openbank.pension.domain.questionnaire.WarningAcknowledgement
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class OnboardingKind { NEW_CONTRACT, TRANSFER_IN }

/**
 * Onboarding lifecycle (ADR-0334 §4 steps 2-3). Every edge is listed here and nowhere else; the
 * aggregate asks [canMoveTo] before every change.
 *
 * Re-answering the questionnaire or picking another strategy before signature is a self-edge or a
 * step back — the participant may change their mind until they sign, and every such change voids
 * the key-information document they were shown.
 */
enum class OnboardingStatus {
    STARTED,
    QUESTIONNAIRE_SUBMITTED,
    KID_ISSUED,
    KID_ACCEPTED,
    SIGNED,
    ACTIVATED,
    REJECTED,
    WITHDRAWN,
    EXPIRED,
    TRANSFER_FAILED,
    ABANDONED,
    ;

    fun canMoveTo(target: OnboardingStatus): Boolean = target in TRANSITIONS.getValue(this)

    val terminal: Boolean get() = TRANSITIONS.getValue(this).isEmpty()

    /** Before signature the participant may still edit, abandon, or let the application lapse. */
    val preSignature: Boolean get() = this in setOf(STARTED, QUESTIONNAIRE_SUBMITTED, KID_ISSUED, KID_ACCEPTED)

    private companion object {
        private val PRE_SIGN_EXITS = setOf(ABANDONED, EXPIRED)
        val TRANSITIONS: Map<OnboardingStatus, Set<OnboardingStatus>> = mapOf(
            STARTED to setOf(QUESTIONNAIRE_SUBMITTED) + PRE_SIGN_EXITS,
            QUESTIONNAIRE_SUBMITTED to setOf(QUESTIONNAIRE_SUBMITTED, KID_ISSUED) + PRE_SIGN_EXITS,
            KID_ISSUED to setOf(QUESTIONNAIRE_SUBMITTED, KID_ISSUED, KID_ACCEPTED) + PRE_SIGN_EXITS,
            KID_ACCEPTED to setOf(QUESTIONNAIRE_SUBMITTED, KID_ISSUED, SIGNED) + PRE_SIGN_EXITS,
            SIGNED to setOf(ACTIVATED, WITHDRAWN, EXPIRED, TRANSFER_FAILED),
            // Activation happens only once the cooling-off period has ended, so nothing follows it here.
            ACTIVATED to emptySet(),
            REJECTED to emptySet(),
            WITHDRAWN to emptySet(),
            EXPIRED to emptySet(),
            TRANSFER_FAILED to emptySet(),
            ABANDONED to emptySet(),
        )
    }
}

/** The provider and contract a transfer-in moves money FROM. */
data class CedingContract(val providerId: String, val providerName: String, val contractNumber: String) {
    init {
        require(providerId.isNotBlank()) { "ceding providerId must not be blank" }
        require(providerName.isNotBlank()) { "ceding providerName must not be blank" }
        require(contractNumber.isNotBlank()) { "ceding contractNumber must not be blank" }
    }
}

/** What the participant declared and what KYC verified, pinned at start. */
data class ApplicantFacts(
    val birthDate: LocalDate,
    val residencyCountry: String?,
    val residencyEvidence: Set<String>,
    val fullLegalCapacity: Boolean,
    val guardianPartyId: UUID?,
)

/** The key-information document issued for one strategy choice; any later choice voids it. */
data class IssuedKid(val documentId: String, val sha256: String, val strategyCode: String, val issuedAt: Instant)

/**
 * A digital onboarding application (ADR-0334 §4). Immutable; every behaviour returns a new
 * instance through [moveTo], which refuses an edge [OnboardingStatus] does not list with
 * `IllegalStateException` (409 at the REST edge). [version] is the optimistic-lock counter the
 * repository compares, so a REST call and a workflow activity cannot silently overwrite each other.
 */
@Suppress("TooManyFunctions") // one behaviour per lifecycle edge
data class OnboardingApplication(
    val id: UUID,
    val partyId: UUID,
    val kind: OnboardingKind,
    val productLine: ProductLine,
    val jurisdiction: String,
    val packVersion: Int,
    val providerEntityId: UUID,
    val providerType: ProviderType,
    val schedule: ContributionSchedule,
    val applicant: ApplicantFacts,
    val ceding: CedingContract?,
    val status: OnboardingStatus,
    val rejectionReasons: List<String> = emptyList(),
    val assessmentId: UUID? = null,
    val recommendedStrategy: String? = null,
    val chosenStrategy: String? = null,
    val unsuitableChoiceAcknowledged: Boolean = false,
    val kid: IssuedKid? = null,
    val kidAcceptedAt: Instant? = null,
    val signatureRef: String? = null,
    val signedAt: Instant? = null,
    val contractId: UUID? = null,
    val transferRequestId: UUID? = null,
    val coolingOffEndsOn: LocalDate? = null,
    val expiresOn: LocalDate,
    val closedReason: String? = null,
    /** Partially saved questionnaire answers (question id -> option code), for save-and-resume. */
    val questionnaireDraft: Map<String, String> = emptyMap(),
    /** Warnings acknowledged for the current assessment; re-answering clears them (issue #12384). */
    val warningAcknowledgements: List<WarningAcknowledgement> = emptyList(),
    val version: Long = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require((kind == OnboardingKind.TRANSFER_IN) == (ceding != null)) {
            "a TRANSFER_IN application carries the ceding contract, and only it does"
        }
        require(status != OnboardingStatus.REJECTED || rejectionReasons.isNotEmpty()) {
            "a REJECTED application carries its reasons"
        }
        require(status !in SIGNED_STATES || contractId != null) { "a signed application carries its contract" }
    }

    fun submitQuestionnaire(assessmentId: UUID, recommendation: String, now: Instant): OnboardingApplication {
        val next = moveTo(OnboardingStatus.QUESTIONNAIRE_SUBMITTED, now)
        return next.copy(
            assessmentId = assessmentId,
            recommendedStrategy = recommendation,
            chosenStrategy = null,
            unsuitableChoiceAcknowledged = false,
            kid = null,
            kidAcceptedAt = null,
            warningAcknowledgements = emptyList(),
        )
    }

    /**
     * Re-assessment of an EXISTING contract (ADR-0334, #12384): the participant answered again
     * after the application was activated. The lifecycle does not move; only the assessment in
     * force (and its recommendation) is replaced. The superseded assessment stays on record.
     */
    fun recordReassessment(assessmentId: UUID, recommendation: String, now: Instant): OnboardingApplication {
        check(status == OnboardingStatus.ACTIVATED) { "only an activated contract's application is re-assessed" }
        return copy(assessmentId = assessmentId, recommendedStrategy = recommendation, updatedAt = now)
    }

    /** Acknowledgements given for a strategy change of the activated contract (audit, appended). */
    fun recordPostActivationAcknowledgements(acks: List<WarningAcknowledgement>, now: Instant): OnboardingApplication {
        check(status == OnboardingStatus.ACTIVATED) {
            "only an activated contract's application records change warnings"
        }
        return copy(warningAcknowledgements = warningAcknowledgements + acks, updatedAt = now)
    }

    /** Save-and-resume: answers are kept as given; nothing is scored until submission. */
    fun saveDraft(answers: Map<String, String>, now: Instant): OnboardingApplication {
        check(status.preSignature) { "a $status application can no longer be edited" }
        return copy(questionnaireDraft = answers, updatedAt = now)
    }

    /**
     * Records acknowledgements. Choosing a strategy after a KID was issued voids the KID (the
     * status machine does that on [issueKid]); acknowledging alone changes no status.
     */
    fun acknowledge(acks: List<WarningAcknowledgement>, now: Instant): OnboardingApplication {
        check(status.preSignature) { "a $status application can no longer be edited" }
        val current = checkNotNull(assessmentId) { "the questionnaire must be answered before acknowledging warnings" }
        require(acks.all { it.assessmentId == current }) { "an acknowledgement must name the current assessment" }
        val kept = warningAcknowledgements.filterNot { old ->
            acks.any {
                it.code == old.code && it.strategyCode == old.strategyCode && it.assessmentId == old.assessmentId
            }
        }
        return copy(warningAcknowledgements = kept + acks, updatedAt = now)
    }

    /** Choosing a strategy issues a fresh key-information document for exactly that strategy. */
    fun issueKid(
        strategyCode: String,
        acknowledgedUnsuitable: Boolean,
        kid: IssuedKid,
        now: Instant,
    ): OnboardingApplication {
        check(assessmentId != null) { "the questionnaire must be answered before a strategy is chosen" }
        require(kid.strategyCode == strategyCode) { "the document must be issued for the chosen strategy" }
        return moveTo(OnboardingStatus.KID_ISSUED, now).copy(
            chosenStrategy = strategyCode,
            unsuitableChoiceAcknowledged = acknowledgedUnsuitable,
            kid = kid,
            kidAcceptedAt = null,
        )
    }

    /** Acceptance names the document, so a participant can only accept what they were shown. */
    fun acceptKid(documentId: String, now: Instant): OnboardingApplication {
        val issued = checkNotNull(kid) { "no key-information document has been issued" }
        require(issued.documentId == documentId) { "document $documentId is not the one issued for this application" }
        return moveTo(OnboardingStatus.KID_ACCEPTED, now).copy(kidAcceptedAt = now)
    }

    fun sign(
        signatureRef: String,
        contractId: UUID,
        transferRequestId: UUID?,
        coolingOffEndsOn: LocalDate,
        now: Instant,
    ): OnboardingApplication {
        require(signatureRef.isNotBlank()) { "signatureRef must not be blank" }
        require((kind == OnboardingKind.TRANSFER_IN) == (transferRequestId != null)) {
            "a TRANSFER_IN application signs its transfer request together with the contract"
        }
        check(status.canMoveTo(OnboardingStatus.SIGNED)) {
            "transition $status -> ${OnboardingStatus.SIGNED} is not allowed"
        }
        // One copy: the status and the contract it requires must change together.
        return copy(
            status = OnboardingStatus.SIGNED,
            updatedAt = now,
            signatureRef = signatureRef,
            signedAt = now,
            contractId = contractId,
            transferRequestId = transferRequestId,
            coolingOffEndsOn = coolingOffEndsOn,
        )
    }

    /**
     * The participant, or the verified guardian who applied on their behalf. Every step is taken by
     * one of them; anyone else must not even learn that the application exists.
     */
    fun actableBy(party: UUID): Boolean = party == partyId || party == applicant.guardianPartyId

    /**
     * SIGNED -> ACTIVATED. Enforced HERE, not in a caller: never before the SCA signature (the
     * transition table only leaves SIGNED) and never before the cooling-off period has ended.
     */
    fun activate(today: LocalDate, now: Instant): OnboardingApplication {
        checkNotNull(signatureRef) { "an unsigned application cannot be activated" }
        val endsOn =
            checkNotNull(coolingOffEndsOn) { "an application without a cooling-off period cannot be activated" }
        check(!today.isBefore(endsOn)) { "the cooling-off period runs until $endsOn" }
        return moveTo(OnboardingStatus.ACTIVATED, now)
    }

    fun withdraw(today: LocalDate, now: Instant): OnboardingApplication {
        val endsOn = checkNotNull(coolingOffEndsOn) { "only a signed application can be withdrawn" }
        check(!today.isAfter(endsOn)) { "the cooling-off period ended on $endsOn" }
        return close(OnboardingStatus.WITHDRAWN, "withdrawn by the participant in the cooling-off period", now)
    }

    fun abandon(now: Instant): OnboardingApplication {
        check(status.preSignature) { "a $status application cannot be abandoned" }
        return close(OnboardingStatus.ABANDONED, "abandoned by the participant", now)
    }

    fun expire(reason: String, now: Instant): OnboardingApplication = close(OnboardingStatus.EXPIRED, reason, now)

    fun transferFailed(reason: String, now: Instant): OnboardingApplication =
        close(OnboardingStatus.TRANSFER_FAILED, reason, now)

    private fun close(target: OnboardingStatus, reason: String, now: Instant): OnboardingApplication =
        moveTo(target, now).copy(closedReason = reason)

    private fun moveTo(target: OnboardingStatus, now: Instant): OnboardingApplication {
        check(status.canMoveTo(target)) { "transition $status -> $target is not allowed" }
        return copy(status = target, updatedAt = now)
    }

    companion object {
        private val SIGNED_STATES = setOf(
            OnboardingStatus.SIGNED,
            OnboardingStatus.ACTIVATED,
            OnboardingStatus.WITHDRAWN,
            OnboardingStatus.TRANSFER_FAILED,
        )

        @Suppress("LongParameterList")
        fun start(
            partyId: UUID,
            kind: OnboardingKind,
            productLine: ProductLine,
            jurisdiction: String,
            packVersion: Int,
            providerEntityId: UUID,
            providerType: ProviderType,
            schedule: ContributionSchedule,
            applicant: ApplicantFacts,
            ceding: CedingContract?,
            ineligibilityReasons: List<String>,
            expiresOn: LocalDate,
            now: Instant,
        ): OnboardingApplication = OnboardingApplication(
            id = Ids.newId(),
            partyId = partyId,
            kind = kind,
            productLine = productLine,
            jurisdiction = jurisdiction,
            packVersion = packVersion,
            providerEntityId = providerEntityId,
            providerType = providerType,
            schedule = schedule,
            applicant = applicant,
            ceding = ceding,
            // An ineligible applicant is recorded, not discarded: the refusal is evidence too.
            status = if (ineligibilityReasons.isEmpty()) OnboardingStatus.STARTED else OnboardingStatus.REJECTED,
            rejectionReasons = ineligibilityReasons,
            expiresOn = expiresOn,
            createdAt = now,
            updatedAt = now,
        )
    }
}
