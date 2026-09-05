// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.domain.model

import com.openbank.libs.domain.identifiers.Ids
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class CaseStatus {
    IDENTIFIER_ENTERED,
    REGISTRY_VERIFIED,
    INITIATOR_MATCHED,
    AWAITING_COSIGNERS,
    READY_TO_SIGN,
    SIGNED,
    ACTIVE,
    MANUAL_REVIEW,
    REJECTED,
    ABANDONED,
    ;

    val isTerminal: Boolean get() = this == ACTIVE || this == REJECTED || this == ABANDONED
}

enum class SignerStatus { INVITED, IDENTIFIED, SIGNED, DECLINED }

/**
 * One person who must sign the framework agreement for the entity. The initiator is a signer
 * too. [partyId] is null until the person has onboarded AS THEMSELVES and verified their identity
 * — an invitation token is never a credential, it only ties the later individual onboarding back
 * to this case.
 */
data class Signer(
    val id: UUID,
    /** Index into [BusinessOnboardingCase.extract]`.representatives`, or null for a manually added signer. */
    val representativeIndex: Int?,
    val fullName: String,
    val dateOfBirth: LocalDate?,
    val partyId: UUID?,
    val status: SignerStatus,
    val invitationToken: String?,
    val invitedAt: Instant?,
    val identifiedAt: Instant?,
    val signedAt: Instant?,
    val signatureRef: String?,
    val isInitiator: Boolean,
)

class CaseTransitionException(message: String) : IllegalStateException(message)

/**
 * The multi-party business onboarding aggregate (ADR-0284 D1). Every transition is a pure function
 * returning a new copy; the use case persists it together with the event it produced.
 */
@Suppress("TooManyFunctions") // one transition per method; the count belongs to the state machine
data class BusinessOnboardingCase(
    val id: UUID,
    val identifier: LegalEntityIdentifier,
    val initiatorPartyId: UUID,
    val status: CaseStatus,
    val extract: RegistryExtract?,
    val entityPartyId: UUID?,
    val requiredSignatures: Int?,
    val signers: List<Signer>,
    val reviewReason: String?,
    /** The entity party has passed the KYC + AML gate (ADR-0267) — may arrive before or after the last signature. */
    val entityPartyActive: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
) {

    val initiator: Signer? get() = signers.firstOrNull { it.isInitiator }

    val signedCount: Int get() = signers.count { it.status == SignerStatus.SIGNED }

    /** The register record has been fetched. Decides between the automatic path and manual review. */
    fun registryVerified(extract: RegistryExtract, at: Instant): BusinessOnboardingCase {
        require(status == CaseStatus.IDENTIFIER_ENTERED || status == CaseStatus.MANUAL_REVIEW) {
            "registry verification is not applicable in status $status"
        }
        val required = extract.representationRule.signaturesRequired(extract.representatives.size)
        return when {
            extract.status != EntityStatus.ACTIVE -> review(extract, "entity is ${extract.status} in the register", at)
            extract.verification == ExtractVerification.UNVERIFIED -> review(
                extract,
                "extract awaits operator attestation",
                at,
            )
            required == null -> review(
                extract,
                "representation rule could not be parsed: ${extract.representationRule.sourceText}",
                at,
            )
            else -> copy(
                status = CaseStatus.REGISTRY_VERIFIED,
                extract = extract,
                requiredSignatures = required,
                updatedAt = at,
            )
        }
    }

    private fun review(extract: RegistryExtract, reason: String, at: Instant) =
        copy(status = CaseStatus.MANUAL_REVIEW, extract = extract, reviewReason = reason, updatedAt = at)

    /** party-service has created the entity party. */
    fun entityPartyCreated(partyId: UUID, at: Instant): BusinessOnboardingCase =
        copy(entityPartyId = partyId, updatedAt = at)

    /**
     * The initiator claims to be the listed representative at [representativeIndex]. A sole
     * trader is always their own (and only) representative. A claim that does not match a
     * listed person is not refused — it is a power-of-attorney situation and goes to review.
     */
    fun initiatorMatched(
        representativeIndex: Int?,
        claimedName: String,
        dateOfBirth: LocalDate?,
        at: Instant,
    ): BusinessOnboardingCase {
        require(status == CaseStatus.REGISTRY_VERIFIED) { "initiator can only be matched after registry verification" }
        val ex = requireNotNull(extract)
        val rep = representativeIndex?.let { ex.representatives.getOrNull(it) }
        if (!ex.isSoleTrader && rep == null) {
            return copy(
                status = CaseStatus.MANUAL_REVIEW,
                reviewReason = "initiator '$claimedName' is not a listed representative — power of attorney required",
                signers = listOf(initiatorSigner(null, claimedName, dateOfBirth, at)),
                updatedAt = at,
            )
        }
        val signer =
            initiatorSigner(representativeIndex, rep?.fullName ?: claimedName, rep?.dateOfBirth ?: dateOfBirth, at)
        val next = copy(status = CaseStatus.INITIATOR_MATCHED, signers = listOf(signer), updatedAt = at)
        return if (requireNotNull(requiredSignatures) <= 1) next.copy(status = CaseStatus.READY_TO_SIGN) else next
    }

    private fun initiatorSigner(index: Int?, name: String, dob: LocalDate?, at: Instant) = Signer(
        id = Ids.newId(),
        representativeIndex = index,
        fullName = name,
        dateOfBirth = dob,
        partyId = initiatorPartyId,
        status = SignerStatus.IDENTIFIED,
        invitationToken = null,
        invitedAt = null,
        identifiedAt = at,
        signedAt = null,
        signatureRef = null,
        isInitiator = true,
    )

    /**
     * The initiator picks which OTHER listed representatives must co-sign. Enough must be chosen
     * to reach [requiredSignatures]; more is allowed (a company may want every director on it).
     */
    fun cosignersInvited(representativeIndexes: List<Int>, tokens: List<String>, at: Instant): BusinessOnboardingCase {
        require(status == CaseStatus.INITIATOR_MATCHED || status == CaseStatus.AWAITING_COSIGNERS) {
            "co-signers can only be invited after the initiator is matched (status $status)"
        }
        val ex = requireNotNull(extract)
        val required = requireNotNull(requiredSignatures)
        val initiatorIndex = initiator?.representativeIndex
        val distinct = representativeIndexes.distinct().filter { it != initiatorIndex }
        require(distinct.all { it in ex.representatives.indices }) { "unknown representative index" }
        require(tokens.size == distinct.size) { "one invitation token per invitee" }
        val invited = distinct.mapIndexed { i, idx ->
            val rep = ex.representatives[idx]
            Signer(
                id = Ids.newId(),
                representativeIndex = idx,
                fullName = rep.fullName,
                dateOfBirth = rep.dateOfBirth,
                partyId = null,
                status = SignerStatus.INVITED,
                invitationToken = tokens[i],
                invitedAt = at,
                identifiedAt = null,
                signedAt = null,
                signatureRef = null,
                isInitiator = false,
            )
        }
        val existingNonInitiator = signers.filter { !it.isInitiator && it.representativeIndex !in distinct }
        val all = listOfNotNull(initiator) + existingNonInitiator + invited
        if (all.size < required) {
            throw CaseTransitionException("the representation rule needs $required signers; ${all.size} selected")
        }
        return copy(status = CaseStatus.AWAITING_COSIGNERS, signers = all, updatedAt = at)
    }

    /** An invited person has onboarded as themselves and holds a verified individual party. */
    fun signerIdentified(token: String, partyId: UUID, at: Instant): BusinessOnboardingCase {
        val signer = signers.firstOrNull { it.invitationToken == token && it.status == SignerStatus.INVITED }
            ?: throw CaseTransitionException("no open invitation for this token")
        if (signers.any {
                it.partyId == partyId
            }
        ) {
            throw CaseTransitionException("this person is already a signer on the case")
        }
        val updated = signers.map {
            if (it.id ==
                signer.id
            ) {
                it.copy(partyId = partyId, status = SignerStatus.IDENTIFIED, identifiedAt = at)
            } else {
                it
            }
        }
        return copy(signers = updated, updatedAt = at).recomputeReadiness(at)
    }

    /** One identified signer has completed the signature ceremony. */
    fun signed(partyId: UUID, signatureRef: String, at: Instant): BusinessOnboardingCase {
        require(status == CaseStatus.READY_TO_SIGN || status == CaseStatus.AWAITING_COSIGNERS) {
            "signing is not open in status $status"
        }
        val signer = signers.firstOrNull { it.partyId == partyId }
            ?: throw CaseTransitionException("party $partyId is not a signer on this case")
        if (signer.status !=
            SignerStatus.IDENTIFIED
        ) {
            throw CaseTransitionException("signer is ${signer.status}, expected IDENTIFIED")
        }
        val updated = signers.map {
            if (it.id ==
                signer.id
            ) {
                it.copy(status = SignerStatus.SIGNED, signedAt = at, signatureRef = signatureRef)
            } else {
                it
            }
        }
        val next = copy(signers = updated, updatedAt = at)
        if (next.signedCount < requireNotNull(requiredSignatures)) return next
        return if (entityPartyActive) next.copy(status = CaseStatus.ACTIVE) else next.copy(status = CaseStatus.SIGNED)
    }

    /** The entity party passed the KYC + AML activation gate (ADR-0267); the relationship is live. */
    fun entityPartyActivated(at: Instant): BusinessOnboardingCase {
        require(!status.isTerminal) { "case is already $status" }
        val flagged = copy(entityPartyActive = true, updatedAt = at)
        return if (status == CaseStatus.SIGNED) flagged.copy(status = CaseStatus.ACTIVE) else flagged
    }

    fun rejected(reason: String, at: Instant): BusinessOnboardingCase {
        require(!status.isTerminal) { "case is already $status" }
        return copy(status = CaseStatus.REJECTED, reviewReason = reason, updatedAt = at)
    }

    fun abandoned(at: Instant): BusinessOnboardingCase {
        require(!status.isTerminal) { "case is already $status" }
        return copy(status = CaseStatus.ABANDONED, updatedAt = at)
    }

    /** An operator has confirmed a manually attested extract or accepted a power of attorney. */
    fun reviewResolved(requiredSignatures: Int, at: Instant): BusinessOnboardingCase {
        require(status == CaseStatus.MANUAL_REVIEW) { "not under review" }
        require(requiredSignatures >= 1) { "at least one signature is required" }
        val next = copy(requiredSignatures = requiredSignatures, reviewReason = null, updatedAt = at)
        return if (initiator == null) {
            next.copy(status = CaseStatus.REGISTRY_VERIFIED)
        } else {
            next.copy(status = CaseStatus.INITIATOR_MATCHED).recomputeReadiness(at)
        }
    }

    private fun recomputeReadiness(at: Instant): BusinessOnboardingCase {
        val required = requiredSignatures ?: return this
        val identified = signers.count { it.status == SignerStatus.IDENTIFIED || it.status == SignerStatus.SIGNED }
        val collecting = status == CaseStatus.AWAITING_COSIGNERS || status == CaseStatus.INITIATOR_MATCHED
        return if (collecting &&
            identified >= required
        ) {
            copy(status = CaseStatus.READY_TO_SIGN, updatedAt = at)
        } else {
            this
        }
    }

    companion object {
        fun start(id: UUID, identifier: LegalEntityIdentifier, initiatorPartyId: UUID, at: Instant) =
            BusinessOnboardingCase(
                id = id,
                identifier = identifier,
                initiatorPartyId = initiatorPartyId,
                status = CaseStatus.IDENTIFIER_ENTERED,
                extract = null,
                entityPartyId = null,
                requiredSignatures = null,
                signers = emptyList(),
                reviewReason = null,
                createdAt = at,
                updatedAt = at,
            )
    }
}
