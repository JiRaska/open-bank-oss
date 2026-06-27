// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.usecase

import com.openbank.libs.domain.case.CaseReasonCode
import com.openbank.libs.domain.case.CaseType
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.pid.application.port.`in`.AddRelationshipCommand
import com.openbank.pid.application.port.`in`.ChangePartyStatusCommand
import com.openbank.pid.application.port.`in`.CreatePartyCommand
import com.openbank.pid.application.port.`in`.LinkCaseEvidenceCommand
import com.openbank.pid.application.port.`in`.LinkExternalIdCommand
import com.openbank.pid.application.port.`in`.TerminateRelationshipCommand
import com.openbank.pid.application.port.`in`.TransitionPartyCaseCommand
import com.openbank.pid.application.port.out.EvidenceLinkPort
import com.openbank.pid.application.port.out.PartyEventPublisher
import com.openbank.pid.application.port.out.PartyRelationshipRepository
import com.openbank.pid.application.port.out.PartyRepository
import com.openbank.pid.domain.event.CaseCreatedEvent
import com.openbank.pid.domain.event.CaseEvidenceLinkedEvent
import com.openbank.pid.domain.event.CaseTransitionedEvent
import com.openbank.pid.domain.event.ExternalIdLinkedEvent
import com.openbank.pid.domain.event.PartyCreatedEvent
import com.openbank.pid.domain.event.PartyStatusChangedEvent
import com.openbank.pid.domain.event.RelationshipAddedEvent
import com.openbank.pid.domain.event.RelationshipTerminatedEvent
import com.openbank.pid.domain.model.AmlRiskScore
import com.openbank.pid.domain.model.ContactAttributes
import com.openbank.pid.domain.model.CoreAttributes
import com.openbank.pid.domain.model.ExternalId
import com.openbank.pid.domain.model.ExternalIdType
import com.openbank.pid.domain.model.KycAttributes
import com.openbank.pid.domain.model.KycLevel
import com.openbank.pid.domain.model.OnboardingChannel
import com.openbank.pid.domain.model.Party
import com.openbank.pid.domain.model.PartyCaseLifecycle
import com.openbank.pid.domain.model.PartyRelationship
import com.openbank.pid.domain.model.PartyRole
import com.openbank.pid.domain.model.PartyStatus
import com.openbank.pid.domain.model.PartyType
import com.openbank.pid.domain.model.RelationshipStatus
import com.openbank.pid.domain.model.VerificationSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import com.openbank.libs.domain.case.CaseStatus as LifecycleCaseStatus

class PartyServiceTest {

    private val partyRepository = mockk<PartyRepository>()
    private val relationshipRepository = mockk<PartyRelationshipRepository>()
    private val eventPublisher = mockk<PartyEventPublisher>(relaxed = true)
    private val evidenceLinkPort = mockk<EvidenceLinkPort>(relaxed = true)

    // A fixed test pepper (32 bytes, hex-encoded) that matches the one in IdentityResolutionServiceTest.
    private val testPepperHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    private val identityResolutionService = IdentityResolutionService(
        mockk(relaxed = true), // repo not called during blind-index compute
        mockk(relaxed = true), // adjudication not called during blind-index compute
        java.util.Optional.of(testPepperHex),
    )
    private val testClock: Clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC)
    private val service = PartyService(
        partyRepository,
        relationshipRepository,
        eventPublisher,
        identityResolutionService,
        evidenceLinkPort,
        testClock,
    )

    @Test
    fun `createParty saves party with ACTIVE status`(): Unit = runBlocking {
        val savedParty = testParty()
        val partySlot = slot<Party>()

        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns false
        coEvery { partyRepository.save(capture(partySlot)) } returns savedParty

        val result = service.createParty(createCommand())

        assertThat(partySlot.captured.status).isEqualTo(PartyStatus.ACTIVE)
        assertThat(partySlot.captured.externalIds).hasSize(1)
        assertThat(partySlot.captured.relationships).hasSize(1)
        assertThat(partySlot.captured.caseLifecycle).isNotNull()
        assertThat(partySlot.captured.caseLifecycle?.caseType).isEqualTo(CaseType.PID_VERIFICATION)
        assertThat(partySlot.captured.caseLifecycle?.status).isEqualTo(LifecycleCaseStatus.OPEN)
        assertThat(partySlot.captured.caseLifecycle?.lastReasonCode).isEqualTo(CaseReasonCode.CREATED)
        assertThat(partySlot.captured.caseLifecycle?.metadata)
            .containsEntry("source", VerificationSource.BANKID.name)
            .containsEntry("channel", OnboardingChannel.BANKID.name)
        assertThat(result).isSameAs(savedParty)
    }

    @Test
    fun `createParty throws when bankIdSub already exists`(): Unit = runBlocking {
        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns true

        assertThatThrownBy { runBlocking { service.createParty(createCommand()) } }
            .isInstanceOf(PartyAlreadyExistsException::class.java)

        coVerify(exactly = 0) { partyRepository.save(any()) }
        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `createParty publishes PartyCreatedEvent and RelationshipAddedEvent`(): Unit = runBlocking {
        val savedParty = testParty()
        val partySlot = slot<Party>()
        val eventSlot = mutableListOf<DomainEvent>()

        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns false
        coEvery { partyRepository.save(capture(partySlot)) } returns savedParty
        coEvery { eventPublisher.publish(capture(eventSlot)) } returns Unit

        service.createParty(createCommand())

        assertThat(eventSlot).hasSize(3)
        assertThat(eventSlot[0]).isInstanceOf(PartyCreatedEvent::class.java)
        assertThat(eventSlot[1]).isInstanceOf(CaseCreatedEvent::class.java)
        assertThat(eventSlot[2]).isInstanceOf(RelationshipAddedEvent::class.java)
        coVerify(exactly = 3) { eventPublisher.publish(any()) }
    }

    @Test
    fun `getById returns party from repo`(): Unit = runBlocking {
        val party = testParty()
        coEvery { partyRepository.findById(party.id) } returns party

        val result = service.getById(party.id)

        assertThat(result).isEqualTo(party)
    }

    @Test
    fun `getById throws PartyNotFoundException when not found`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        coEvery { partyRepository.findById(id) } returns null

        assertThatThrownBy { runBlocking { service.getById(id) } }
            .isInstanceOf(PartyNotFoundException::class.java)
    }

    @Test
    fun `changeStatus updates status and publishes event`(): Unit = runBlocking {
        val party = testParty()
        val updatedSlot = slot<Party>()
        val eventSlot = slot<DomainEvent>()

        coEvery { partyRepository.findById(party.id) } returns party
        coEvery { partyRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }
        coEvery { eventPublisher.publish(capture(eventSlot)) } returns Unit

        val result = service.changeStatus(ChangePartyStatusCommand(party.id, PartyStatus.SUSPENDED, "manual review"))

        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.SUSPENDED)
        assertThat(result.status).isEqualTo(PartyStatus.SUSPENDED)
        assertThat(eventSlot.captured).isInstanceOf(PartyStatusChangedEvent::class.java)
        coVerify(exactly = 1) { eventPublisher.publish(any()) }
    }

    @Test
    fun `transitionCase applies valid PID lifecycle transition`(): Unit = runBlocking {
        val party = testParty().copy(
            caseLifecycle = testCaseLifecycle(status = LifecycleCaseStatus.OPEN),
        )
        val updatedSlot = slot<Party>()

        coEvery { partyRepository.findById(party.id) } returns party
        coEvery { partyRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }
        coEvery { eventPublisher.publish(any()) } returns Unit

        val result = service.transitionCase(
            TransitionPartyCaseCommand(
                partyId = party.id,
                toStatus = LifecycleCaseStatus.IN_REVIEW,
                actor = "pid-reviewer",
                reasonCode = CaseReasonCode.REVIEW_STARTED,
                reason = "docs received",
                metadata = mapOf("step" to "manual-check"),
            ),
        )

        assertThat(updatedSlot.captured.caseLifecycle?.status).isEqualTo(LifecycleCaseStatus.IN_REVIEW)
        assertThat(updatedSlot.captured.caseLifecycle?.lastActor).isEqualTo("pid-reviewer")
        assertThat(updatedSlot.captured.caseLifecycle?.lastReasonCode).isEqualTo(CaseReasonCode.REVIEW_STARTED)
        assertThat(updatedSlot.captured.caseLifecycle?.metadata)
            .containsEntry("reason", "docs received")
            .containsEntry("step", "manual-check")
            .containsEntry("source", VerificationSource.BANKID.name)
        assertThat(result.caseLifecycle?.status).isEqualTo(LifecycleCaseStatus.IN_REVIEW)
        coVerify(exactly = 1) { eventPublisher.publish(match { it is CaseTransitionedEvent }) }
    }

    @Test
    fun `linkCaseEvidence publishes case evidence linked event`(): Unit = runBlocking {
        val party = testParty().copy(
            caseLifecycle = testCaseLifecycle(status = LifecycleCaseStatus.OPEN),
        )
        val eventSlot = slot<DomainEvent>()

        coEvery { partyRepository.findById(party.id) } returns party
        coEvery { eventPublisher.publish(capture(eventSlot)) } returns Unit

        val result = service.linkCaseEvidence(
            LinkCaseEvidenceCommand(
                partyId = party.id,
                evidenceRef = "evidence-123",
                actor = "pid-reviewer",
                linkedAt = OffsetDateTime.parse("2025-01-01T00:10:00Z"),
            ),
        )

        assertThat(result).isEqualTo(party)
        assertThat(eventSlot.captured).isInstanceOf(CaseEvidenceLinkedEvent::class.java)
        coVerify(exactly = 1) { eventPublisher.publish(any()) }
        coVerify(exactly = 1) { evidenceLinkPort.recordLink(party.id, any(), "evidence-123", "pid-reviewer") }
    }

    @Test
    fun `linkCaseEvidence requires initialized case lifecycle`(): Unit = runBlocking {
        val party = testParty()
        coEvery { partyRepository.findById(party.id) } returns party

        assertThatThrownBy {
            runBlocking {
                service.linkCaseEvidence(
                    LinkCaseEvidenceCommand(
                        partyId = party.id,
                        evidenceRef = "evidence-123",
                        actor = "pid-reviewer",
                    ),
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)

        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `transitionCase rejects invalid PID lifecycle transition`(): Unit = runBlocking {
        val party = testParty().copy(
            caseLifecycle = testCaseLifecycle(status = LifecycleCaseStatus.OPEN),
        )

        coEvery { partyRepository.findById(party.id) } returns party

        assertThatThrownBy {
            runBlocking {
                service.transitionCase(
                    TransitionPartyCaseCommand(
                        partyId = party.id,
                        toStatus = LifecycleCaseStatus.CLOSED,
                        actor = "pid-reviewer",
                        reasonCode = CaseReasonCode.CLOSED,
                        reason = "premature close",
                    ),
                )
            }
        }
            .isInstanceOf(InvalidPartyCaseTransitionException::class.java)
            .hasMessageContaining("Transition from OPEN to CLOSED is not allowed")

        coVerify(exactly = 0) { partyRepository.update(any()) }
    }

    @Test
    fun `addRelationship throws when active role exists`(): Unit = runBlocking {
        val party = testParty().copy(
            relationships = listOf(
                testRelationship(role = PartyRole.CUSTOMER, status = RelationshipStatus.ACTIVE),
            ),
        )
        coEvery { partyRepository.findById(party.id) } returns party

        assertThatThrownBy {
            runBlocking {
                service.addRelationship(AddRelationshipCommand(party.id, PartyRole.CUSTOMER, OnboardingChannel.API))
            }
        }.isInstanceOf(RelationshipAlreadyExistsException::class.java)

        coVerify(exactly = 0) { relationshipRepository.save(any()) }
    }

    @Test
    fun `terminateRelationship sets TERMINATED status`(): Unit = runBlocking {
        val party = testParty()
        val relationship = testRelationship(partyId = party.id)
        val updatedSlot = slot<PartyRelationship>()
        val eventSlot = slot<DomainEvent>()

        coEvery { relationshipRepository.findById(relationship.id) } returns relationship
        coEvery { relationshipRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }
        coEvery { eventPublisher.publish(capture(eventSlot)) } returns Unit

        val result = service.terminateRelationship(
            TerminateRelationshipCommand(party.id, relationship.id, "customer request"),
        )

        assertThat(updatedSlot.captured.status).isEqualTo(RelationshipStatus.TERMINATED)
        assertThat(result.status).isEqualTo(RelationshipStatus.TERMINATED)
        assertThat(result.terminatedAt).isNotNull()
        assertThat(eventSlot.captured).isInstanceOf(RelationshipTerminatedEvent::class.java)
        coVerify(exactly = 1) { eventPublisher.publish(any()) }
    }

    // ── blind-index storage on party creation (ADR-0072) ─────────────────────

    // A known-valid Czech RČ: 760506/0001 → birthdate 1976-05-06, MALE, passes mod-11
    private val validRc = "7605060001"

    @Test
    fun `createParty stores BIRTH_NUMBER blind index when valid RC is supplied`(): Unit = runBlocking {
        val savedParty = testParty()
        val partySlot = slot<Party>()

        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns false
        coEvery { partyRepository.save(capture(partySlot)) } returns savedParty

        service.createParty(createCommand(birthNumberRaw = validRc))

        val extIds = partySlot.captured.externalIds
        val birthNumberId = extIds.firstOrNull { it.type == ExternalIdType.BIRTH_NUMBER }
        assertThat(birthNumberId).isNotNull()
        // Value must be a 64-char hex string — the HMAC-SHA256 blind index.
        assertThat(birthNumberId!!.value).hasSize(64).matches("[0-9a-f]+")
    }

    @Test
    fun `createParty blind index is deterministic for the same RC and pepper`(): Unit = runBlocking {
        val savedParty = testParty()
        val slot1 = slot<Party>()
        val slot2 = slot<Party>()

        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns false
        coEvery { partyRepository.save(capture(slot1)) } returns savedParty

        service.createParty(createCommand(birthNumberRaw = validRc))

        coEvery { partyRepository.save(capture(slot2)) } returns savedParty
        service.createParty(createCommand(birthNumberRaw = validRc))

        val idx1 = slot1.captured.externalIds.first { it.type == ExternalIdType.BIRTH_NUMBER }.value
        val idx2 = slot2.captured.externalIds.first { it.type == ExternalIdType.BIRTH_NUMBER }.value
        assertThat(idx1).isEqualTo(idx2)
    }

    @Test
    fun `createParty does NOT store BIRTH_NUMBER blind index when no RC is supplied`(): Unit = runBlocking {
        val savedParty = testParty()
        val partySlot = slot<Party>()

        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns false
        coEvery { partyRepository.save(capture(partySlot)) } returns savedParty

        service.createParty(createCommand(birthNumberRaw = null))

        assertThat(partySlot.captured.externalIds.none { it.type == ExternalIdType.BIRTH_NUMBER }).isTrue()
    }

    @Test
    fun `createParty does NOT store BIRTH_NUMBER blind index when RC is invalid`(): Unit = runBlocking {
        val savedParty = testParty()
        val partySlot = slot<Party>()

        coEvery { partyRepository.existsByExternalId(ExternalIdType.BANKID_SUB, "sub-1") } returns false
        coEvery { partyRepository.save(capture(partySlot)) } returns savedParty

        service.createParty(createCommand(birthNumberRaw = "123456")) // invalid RČ — checksum fails

        assertThat(partySlot.captured.externalIds.none { it.type == ExternalIdType.BIRTH_NUMBER }).isTrue()
    }

    // ── end of blind-index tests ──────────────────────────────────────────────

    private fun createCommand(birthNumberRaw: String? = null) = CreatePartyCommand(
        partyType = PartyType.NATURAL_PERSON,
        givenName = "Jan",
        familyName = "Novák",
        birthdate = LocalDate.of(1990, 1, 1),
        birthNumberEncrypted = null,
        birthNumberRaw = birthNumberRaw,
        nationalities = listOf("CZ"),
        verificationSource = VerificationSource.BANKID,
        bankIdSub = "sub-1",
        initialRole = PartyRole.CUSTOMER,
        onboardingChannel = OnboardingChannel.BANKID,
    )

    private fun testParty(id: UUID = UUID.randomUUID()) = Party(
        id = id,
        partyType = PartyType.NATURAL_PERSON,
        status = PartyStatus.ACTIVE,
        externalIds = listOf(ExternalId(ExternalIdType.BANKID_SUB, "sub-1")),
        coreAttributes = CoreAttributes(
            givenName = "Jan",
            familyName = "Novák",
            birthdate = LocalDate.of(1990, 1, 1),
            birthNumberEncrypted = null,
            gender = null,
            birthplace = null,
            nationalities = listOf("CZ"),
            idDocuments = emptyList(),
            verificationSource = VerificationSource.BANKID,
            verifiedAt = now(),
        ),
        addressAttributes = null,
        contactAttributes = ContactAttributes(
            email = null,
            emailVerifiedAt = null,
            phone = null,
            phoneVerifiedAt = null,
        ),
        kycAttributes = KycAttributes(
            kycLevel = KycLevel.BASIC,
            kycCompletedAt = now(),
            kycExpiresAt = now().plusYears(1),
            amlRiskScore = AmlRiskScore.LOW,
            pepFlag = false,
            sanctionsFlag = false,
            uboVerifiedAt = null,
            lastAmlReviewAt = now(),
        ),
        relationships = emptyList(),
        caseLifecycle = null,
        createdAt = now(),
        updatedAt = now(),
        version = 0,
    )

    private fun testCaseLifecycle(status: LifecycleCaseStatus = LifecycleCaseStatus.OPEN) = PartyCaseLifecycle(
        caseId = com.openbank.libs.domain.case.CaseId(UUID.fromString("00000000-0000-0000-0000-000000000123")),
        caseType = CaseType.PID_VERIFICATION,
        status = status,
        lastActor = "pid:bankid:bankid",
        lastReasonCode = CaseReasonCode.CREATED,
        lastTransitionAt = now(),
        metadata = mapOf(
            "source" to VerificationSource.BANKID.name,
            "channel" to OnboardingChannel.BANKID.name,
            "partyId" to "party-1",
        ),
    )

    private fun testRelationship(
        id: UUID = UUID.randomUUID(),
        partyId: UUID = UUID.randomUUID(),
        role: PartyRole = PartyRole.CUSTOMER,
        status: RelationshipStatus = RelationshipStatus.ACTIVE,
    ) = PartyRelationship(
        id = id,
        partyId = partyId,
        role = role,
        status = status,
        onboardedAt = now(),
        onboardingChannel = OnboardingChannel.BANKID,
        terminatedAt = null,
        terminationReason = null,
    )

    private fun now(): OffsetDateTime = OffsetDateTime.parse("2025-01-01T00:00:00Z")

    @Test
    fun `linkExternalId adds a new external id and publishes ExternalIdLinkedEvent`(): Unit = runBlocking {
        val party = testParty()
        val updatedSlot = slot<Party>()
        val eventSlot = slot<DomainEvent>()
        coEvery { partyRepository.findById(party.id) } returns party
        coEvery { partyRepository.existsByExternalId(ExternalIdType.KEYCLOAK_ID, "sub-new") } returns false
        coEvery { partyRepository.update(capture(updatedSlot)) } answers { updatedSlot.captured }
        coEvery { eventPublisher.publish(capture(eventSlot)) } returns Unit

        val result = service.linkExternalId(LinkExternalIdCommand(party.id, ExternalIdType.KEYCLOAK_ID, "sub-new"))

        assertThat(updatedSlot.captured.externalIds)
            .anyMatch { it.type == ExternalIdType.KEYCLOAK_ID && it.value == "sub-new" }
        assertThat(eventSlot.captured).isInstanceOf(ExternalIdLinkedEvent::class.java)
        assertThat(result.version).isEqualTo(party.version + 1)
    }

    @Test
    fun `linkExternalId is idempotent when the id is already on the party`(): Unit = runBlocking {
        val base = testParty()
        val party = base.copy(externalIds = base.externalIds + ExternalId(ExternalIdType.KEYCLOAK_ID, "sub-dup"))
        coEvery { partyRepository.findById(party.id) } returns party

        val result = service.linkExternalId(LinkExternalIdCommand(party.id, ExternalIdType.KEYCLOAK_ID, "sub-dup"))

        assertThat(result).isEqualTo(party)
        coVerify(exactly = 0) { partyRepository.update(any()) }
        coVerify(exactly = 0) { eventPublisher.publish(any()) }
    }

    @Test
    fun `linkExternalId rejects an id already linked to another party`(): Unit = runBlocking {
        val party = testParty()
        coEvery { partyRepository.findById(party.id) } returns party
        coEvery { partyRepository.existsByExternalId(ExternalIdType.KEYCLOAK_ID, "sub-taken") } returns true

        assertThatThrownBy {
            runBlocking {
                service.linkExternalId(LinkExternalIdCommand(party.id, ExternalIdType.KEYCLOAK_ID, "sub-taken"))
            }
        }.isInstanceOf(PartyAlreadyExistsException::class.java)
        coVerify(exactly = 0) { partyRepository.update(any()) }
    }
}
