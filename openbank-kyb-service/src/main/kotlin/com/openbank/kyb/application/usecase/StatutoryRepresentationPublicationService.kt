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

@ApplicationScoped
class StatutoryRepresentationPublicationService(
    private val cases: BusinessOnboardingCaseRepository,
    private val attestations: RepresentationAttestationRepository,
    private val publisher: StatutoryRepresentationPublisher,
) {
    suspend fun publish(caseId: UUID, expectedAttestationId: UUID): UUID? {
        val case = cases.findById(caseId) ?: return null
        val entityPartyId = case.entityPartyId ?: return null
        val evidence = evidenceFor(case) ?: return null
        if (evidence.attestationId != expectedAttestationId) return null
        publisher.publishStatutoryRule(entityPartyId, evidence)
        return evidence.attestationId
    }

    suspend fun isCurrent(caseId: UUID, entityPartyId: UUID, attestationId: UUID): Boolean {
        val case = cases.findById(caseId) ?: return false
        return case.entityPartyId == entityPartyId && evidenceFor(case)?.attestationId == attestationId
    }

    private suspend fun evidenceFor(case: BusinessOnboardingCase): StatutoryRepresentationEvidence? {
        val extract = case.verifiedCompanyExtract() ?: return null
        val ruleText = extract.representationRule.sourceText?.takeIf { it.isNotBlank() } ?: return null
        val attestation = attestations.findLatestFor(case.identifier) ?: return null
        if (!attestation.matches(case, RepresentationAttestation.hashOf(ruleText))) return null
        val mode = attestation.confirmedMode ?: return null
        if (mode !in setOf(RepresentationMode.JOINT_N, RepresentationMode.JOINT_ALL)) return null

        val signed = case.signedStatutoryRoster(extract.representatives.indices) ?: return null
        if (attestation.confirmedSigners !in 2..signed.size) return null
        if (mode == RepresentationMode.JOINT_ALL && attestation.confirmedSigners != signed.size) {
            return null
        }

        val slotsByParty = case.statutoryOfficeSlots(signed)
        return StatutoryRepresentationEvidence(
            caseId = case.id,
            attestationId = attestation.id,
            ruleTextHash = attestation.ruleTextHash,
            attestedAt = attestation.attestedAt,
            mode = mode,
            requiredSignatures = attestation.confirmedSigners,
            requiredOffices = attestation.confirmedRoles,
            representatives = signed.map { signer ->
                val partyId = requireNotNull(signer.partyId)
                val digest = InputDigest.sha256(mapOf("evidenceRef" to case.mandateEvidenceRef(signer)))
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

    /** An unlinked or unsigned register representative leaves the roster incomplete. */
    private fun BusinessOnboardingCase.signedStatutoryRoster(registerIndices: IntRange): List<Signer>? {
        val roster = signers.filter {
            it.isSignedWithEvidence() && it.partyId != null && it.representativeIndex != null
        }
        if (roster.size != registerIndices.count()) return null
        if (roster.map { it.representativeIndex }.toSet() != registerIndices.toSet()) return null
        if (roster.map { it.partyId }.toSet().size != roster.size) return null
        if (roster.map { it.signatureRef }.toSet().size != roster.size) return null
        return roster
    }

    private fun Signer.isSignedWithEvidence() = status == SignerStatus.SIGNED && signatureRef?.isNotBlank() == true

    private fun RepresentationAttestation.matches(case: BusinessOnboardingCase, actualHash: String): Boolean =
        isActive &&
            id == case.representationAttestationId &&
            ruleTextHash == actualHash &&
            !attestedBy.startsWith("system:") &&
            confirmedSigners == case.requiredSignatures &&
            confirmedRoles == case.requiredSignerRoles
}
