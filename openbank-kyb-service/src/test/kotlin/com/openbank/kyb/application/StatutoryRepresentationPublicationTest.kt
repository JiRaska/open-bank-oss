// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application

import com.openbank.kyb.application.port.out.BusinessOnboardingCaseRepository
import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.application.port.out.StatutoryRepresentationEvidence
import com.openbank.kyb.application.port.out.StatutoryRepresentationPublisher
import com.openbank.kyb.application.usecase.StatutoryRepresentationPublicationService
import com.openbank.kyb.domain.model.BusinessOnboardingCase
import com.openbank.kyb.domain.model.CaseStatus
import com.openbank.kyb.domain.model.EntityStatus
import com.openbank.kyb.domain.model.ExtractVerification
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.LegalFormClass
import com.openbank.kyb.domain.model.RegistryExtract
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationDecision
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.domain.model.RepresentationRule
import com.openbank.kyb.domain.model.Representative
import com.openbank.kyb.domain.model.Signer
import com.openbank.kyb.domain.model.SignerStatus
import com.openbank.libs.audit.decision.InputDigest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StatutoryRepresentationPublicationTest {
    private val cases = mockk<BusinessOnboardingCaseRepository>()
    private val attestations = mockk<RepresentationAttestationRepository>()
    private val party = mockk<StatutoryRepresentationPublisher>()
    private val service = StatutoryRepresentationPublicationService(cases, attestations, party)

    private val instant = Instant.parse("2026-08-01T00:00:00Z")
    private val identifier = LegalEntityIdentifier.of(IdentifierScheme.CZ_ICO, "00000001")
    private val entity = UUID.randomUUID()
    private val caseId = UUID.randomUUID()
    private val humans = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val text = "Společně jedná předseda a místopředseda."
    private val offices = listOf("predseda", "mistopredseda")
    private val attestation = RepresentationAttestation(
        id = UUID.randomUUID(),
        identifier = identifier,
        ruleTextHash = RepresentationAttestation.hashOf(text),
        ruleText = text,
        parsedMode = RepresentationMode.JOINT_N,
        parsedSigners = 2,
        confirmedSigners = 2,
        confirmedRoles = offices,
        attestedBy = "fixture-kyc-reviewer",
        attestedAt = instant,
        confirmedMode = RepresentationMode.JOINT_N,
    )

    @Test
    fun `publishes the exact pinned attestation and preserves distinct office assignments`(): Unit = runBlocking {
        val active = activeCase()
        val evidence = slot<StatutoryRepresentationEvidence>()
        coEvery { cases.findById(caseId) } returns active
        coEvery { attestations.findLatestFor(identifier) } returns attestation
        coEvery { party.publishStatutoryRule(entity, capture(evidence)) } returns Unit

        assertThat(service.publish(caseId, UUID.randomUUID())).isNull()
        coVerify(exactly = 0) { party.publishStatutoryRule(any(), any()) }
        assertThat(service.publish(caseId, attestation.id)).isEqualTo(attestation.id)
        assertThat(evidence.captured.ruleTextHash).isEqualTo(attestation.ruleTextHash)
        assertThat(evidence.captured.requiredSignatures).isEqualTo(2)
        assertThat(evidence.captured.requiredOffices).containsExactlyElementsOf(offices)
        assertThat(evidence.captured.representatives.associate { it.partyId to it.officeSlots })
            .containsExactlyInAnyOrderEntriesOf(mapOf(humans[0] to setOf(0), humans[1] to setOf(1)))
        assertThat(evidence.captured.representatives.associate { it.partyId to it.mandateEvidenceHash })
            .containsExactlyInAnyOrderEntriesOf(
                humans.associateWith { human ->
                    InputDigest.sha256(mapOf("evidenceRef" to "kyb-case:$caseId:signature:synthetic-$human")).hex
                },
            )
        assertThat(service.isCurrent(caseId, entity, attestation.id)).isTrue()
        assertThat(service.isCurrent(caseId, UUID.randomUUID(), attestation.id)).isFalse()
    }

    @Test
    fun `a superseded attestation stops being current before the replacement is published`(): Unit = runBlocking {
        coEvery { cases.findById(caseId) } returns activeCase()
        coEvery { attestations.findLatestFor(identifier) } returns attestation.copy(id = UUID.randomUUID())

        assertThat(service.isCurrent(caseId, entity, attestation.id)).isFalse()
        assertThat(service.publish(caseId, attestation.id)).isNull()
        coVerify(exactly = 0) { party.publishStatutoryRule(any(), any()) }
    }

    @Test
    fun `an incomplete or unpinned case cannot publish a rule`(): Unit = runBlocking {
        val complete = activeCase()
        coEvery { cases.findById(caseId) } returnsMany listOf(
            complete.copy(signers = complete.signers.take(1)),
            complete.copy(signers = complete.signers.map { it.copy(signatureRef = "shared-synthetic-reference") }),
            complete.copy(representationAttestationId = null),
        )
        coEvery { attestations.findLatestFor(identifier) } returns attestation

        repeat(3) { assertThat(service.publish(caseId, attestation.id)).isNull() }
        coVerify(exactly = 0) { party.publishStatutoryRule(any(), any()) }
    }

    private fun activeCase(): BusinessOnboardingCase {
        val extract = RegistryExtract(
            identifier = identifier,
            legalName = "Statutory Test Fixture",
            legalFormCode = "112",
            legalFormClass = LegalFormClass.LIMITED_COMPANY,
            status = EntityStatus.ACTIVE,
            registeredAddress = null,
            incorporatedOn = null,
            taxId = null,
            representatives = listOf(
                Representative("Fixture Chair", null, "board", "předseda představenstva", null),
                Representative("Fixture Vice", null, "board", "místopředseda představenstva", null),
            ),
            representationRule = RepresentationRule(RepresentationMode.JOINT_N, 2, text),
            source = "synthetic-fixture",
            sourceRef = null,
            verification = ExtractVerification.VERIFIED,
            fetchedAt = instant,
        )
        return BusinessOnboardingCase.start(caseId, identifier, humans.first(), instant)
            .registryVerified(extract, RepresentationDecision.Attested(attestation), instant)
            .copy(
                status = CaseStatus.ACTIVE,
                entityPartyId = entity,
                entityPartyActive = true,
                signers = humans.mapIndexed { index, human ->
                    Signer(
                        id = UUID.randomUUID(),
                        representativeIndex = index,
                        fullName = "Fixture Representative",
                        dateOfBirth = null,
                        partyId = human,
                        status = SignerStatus.SIGNED,
                        invitationToken = null,
                        invitedAt = null,
                        identifiedAt = instant,
                        signedAt = instant,
                        signatureRef = "synthetic-$human",
                        isInitiator = index == 0,
                    )
                },
            )
    }
}
