// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.libs.audit.decision.InputDigest
import com.openbank.party.application.port.out.PartyMandateRepository
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.application.port.out.StatutoryRepresentationRepository
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.PartyMandate
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import com.openbank.party.domain.model.StatutoryRepresentationEvidence
import com.openbank.party.domain.model.StatutoryRepresentationRule
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class StatutoryRepresentationService(
    private val rules: StatutoryRepresentationRepository,
    private val parties: PartyRepository,
    private val mandates: PartyMandateRepository,
    private val clock: Clock,
) {
    suspend fun publish(
        principalPartyId: UUID,
        submitted: StatutoryRepresentationEvidence,
    ): StatutoryRepresentationRule {
        val now = clock.instant()
        require(!submitted.attestedAt.isAfter(now)) { "the attestation cannot be in the future" }
        val evidence = submitted.copy(
            requiredOffices = submitted.requiredOffices.toList(),
            representatives = submitted.representatives.sortedBy { it.partyId }.map {
                it.copy(officeSlots = it.officeSlots.toSortedSet())
            },
        )
        val live = requireNotNull(verifiedMandates(principalPartyId, evidence, now)) {
            "every representative requires an active statutory mandate from the verified case"
        }
        val proposed = StatutoryRepresentationRule(
            principalPartyId = principalPartyId,
            evidence = evidence,
            mandatesByRepresentative = live.mapValues { it.value.id },
            effectiveFrom = now,
            validTo = live.values.mapNotNull { it.validTo }.minOrNull(),
        )
        val stored = rules.insertOrRead(proposed)
        require(
            stored.principalPartyId == principalPartyId &&
                stored.evidence == proposed.evidence &&
                stored.mandatesByRepresentative == proposed.mandatesByRepresentative,
        ) { "this attestation already records different representation evidence" }
        return stored
    }

    /** The latest published snapshot. A consumer must separately establish that KYB still attests it. */
    suspend fun latestPublished(principalPartyId: UUID): StatutoryRepresentationRule? {
        val stored = rules.findLatest(principalPartyId) ?: return null
        val now = clock.instant()
        if (!stored.isEffectiveAt(now)) return null
        val live = verifiedMandates(principalPartyId, stored.evidence, now) ?: return null
        return stored.takeIf { live.mapValues { it.value.id } == it.mandatesByRepresentative }
    }

    private suspend fun verifiedMandates(
        principalPartyId: UUID,
        evidence: StatutoryRepresentationEvidence,
        now: Instant,
    ): Map<UUID, PartyMandate>? {
        val principal = parties.findById(principalPartyId)
        if (principal?.partyType != PartyType.COMPANY || principal.status != PartyStatus.ACTIVE) return null
        val prefix = "kyb-case:${evidence.caseId}:signature:"
        val eligible = mandates.findByPrincipal(principalPartyId).filter {
            it.isStatutoryFor(evidence.requiredSignatures, prefix, now)
        }.associateBy { it.agentPartyId }
        val verified = linkedMapOf<UUID, PartyMandate>()
        for (representative in evidence.representatives) {
            val actor = parties.findById(representative.partyId)
            if (actor?.partyType != PartyType.INDIVIDUAL || actor.status != PartyStatus.ACTIVE) return null
            val mandate = eligible[representative.partyId] ?: return null
            val evidenceRef = mandate.evidenceRef ?: return null
            if (InputDigest.sha256(mapOf("evidenceRef" to evidenceRef)).hex != representative.mandateEvidenceHash) {
                return null
            }
            verified[representative.partyId] = mandate
        }
        return verified
    }
}

private fun PartyMandate.isStatutoryFor(threshold: Int, evidencePrefix: String, at: Instant): Boolean =
    role == MandateRole.LEGAL_REPRESENTATIVE &&
        authority == MandateAuthority.JOINT &&
        requiredSignatures == threshold &&
        source == MandateSource.REGISTRY &&
        isActiveAt(at) &&
        evidenceRef?.startsWith(evidencePrefix) == true &&
        evidenceRef.length > evidencePrefix.length
