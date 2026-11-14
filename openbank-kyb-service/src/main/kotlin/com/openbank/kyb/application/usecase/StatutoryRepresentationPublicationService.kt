// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.application.port.out.StatutoryRepresentationEvidence
import com.openbank.kyb.application.port.out.StatutoryRepresentationPublisher
import com.openbank.kyb.application.port.out.StatutoryRepresentative
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.Signer
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.libs.audit.decision.InputDigest
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** A publish by the same principal that attested the rule: separation of duties, answered 403. */
class SeparationOfDutiesException(message: String) : RuntimeException(message)

@ApplicationScoped
class StatutoryRepresentationPublicationService(
    private val cases: BusinessOnboardingCaseRepository,
    private val attestations: RepresentationAttestationRepository,
    private val publisher: StatutoryRepresentationPublisher,
) {
    suspend fun publish(caseId: UUID, expectedAttestationId: UUID, publishedBy: String): UUID? {
        val case = cases.findById(caseId) ?: return null
        val entityPartyId = case.entityPartyId ?: return null
        val (attestation, evidence) = resolve(case) ?: return null
        if (evidence.attestationId != expectedAttestationId) return null
        // Four-eyes: the staff member releasing the rule to party-service must not be the one who
        // attested it. Exact principal comparison, never a display name.
        if (publishedBy == attestation.attestedBy) {
            throw SeparationOfDutiesException(
                "the statutory representation rule must be published by someone other than its attester",
            )
        }
        publisher.publishStatutoryRule(entityPartyId, evidence)
        return evidence.attestationId
    }

    suspend fun isCurrent(caseId: UUID, entityPartyId: UUID, attestationId: UUID): Boolean {
        val case = cases.findById(caseId) ?: return false
        return case.entityPartyId == entityPartyId && resolve(case)?.second?.attestationId == attestationId
    }

    private suspend fun resolve(
        case: BusinessOnboardingCase,
    ): Pair<RepresentationAttestation, StatutoryRepresentationEvidence>? {
        val extract = case.verifiedCompanyExtract() ?: return null
        val ruleText = extract.representationRule.sourceText?.takeIf { it.isNotBlank() } ?: return null
        val attestation = attestations.findLatestFor(case.identifier) ?: return null
        if (!attestation.matches(case, RepresentationAttestation.hashOf(ruleText))) return null
        val mode = attestation.confirmedMode ?: return null
        if (mode !in setOf(RepresentationMode.JOINT_N, RepresentationMode.JOINT_ALL)) return null

        val signed = case.signedStatutoryRoster(extract.representatives.indices) ?: return null
        if (attestation.confirmedSigners !in 2..signed.size || case.officeShortfall(signed).isNotEmpty()) return null
        if (mode == RepresentationMode.JOINT_ALL && !signed.coversAll(extract, attestation)) return null

        val slotsByParty = case.statutoryOfficeSlots(signed)
        return attestation to StatutoryRepresentationEvidence(
            caseId = case.id,
            attestationId = attestation.id,
            ruleTextHash = attestation.ruleTextHash,
            attestedAt = attestation.attestedAt,
            mode = mode,
            requiredSignatures = attestation.confirmedSigners,
            requiredOffices = attestation.confirmedRoles,
            representatives = signed.map { signer ->
                val partyId = requireNotNull(signer.partyId)
                val digest = InputDigest.sha256(
                    mapOf(
                        "evidenceRef" to case.mandateEvidenceRef(signer),
                        "representativePartyId" to partyId.toString(),
                    ),
                )
                StatutoryRepresentative(
                    partyId = partyId,
                    officeSlots = slotsByParty.getValue(partyId),
                    mandateEvidenceHash = digest.hex,
                )
            },
        )
    }

    private fun BusinessOnboardingCase.verifiedCompanyExtract(): RegistryExtract? {
        if (status != CaseStatus.ACTIVE) return null
        return extract?.takeIf {
            it.status == EntityStatus.ACTIVE && it.verification == ExtractVerification.VERIFIED && !it.isSoleTrader
        }
    }

    private fun BusinessOnboardingCase.signedStatutoryRoster(registerIndices: IntRange): List<Signer>? {
        val recordedAgreement = agreement ?: return null
        val sharedCeremony = recordedAgreement.ceremonyId.toString()
        val roster = signers.filter { it.status == SignerStatus.SIGNED }
        val valid = roster.all { signer ->
            val human = signer.partyId
            val inRegister = signer.representativeIndex?.let { it in registerIndices } == true
            val signedCeremony = signer.signedAt != null && signer.signatureRef == sharedCeremony
            human != null && recordedAgreement.acceptedByParty(human) && inRegister && signedCeremony
        }
        if (!valid || roster.map { it.representativeIndex }.toSet().size != roster.size) return null
        if (roster.map { it.partyId }.toSet().size != roster.size) return null
        return roster
    }

    private fun List<Signer>.coversAll(extract: RegistryExtract, attestation: RepresentationAttestation) =
        size == extract.representatives.size && size == attestation.confirmedSigners

    private fun RepresentationAttestation.matches(case: BusinessOnboardingCase, actualHash: String): Boolean =
        isActive &&
            id == case.representationAttestationId &&
            ruleTextHash == actualHash &&
            !attestedBy.startsWith("system:") &&
            confirmedSigners == case.requiredSignatures &&
            confirmedRoles == case.requiredSignerRoles
}
