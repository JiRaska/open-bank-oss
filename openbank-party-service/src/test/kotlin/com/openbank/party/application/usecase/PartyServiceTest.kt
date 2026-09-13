// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.`in`.AddDocumentCommand
import com.openbank.party.application.port.`in`.CreatePartyCommand
import com.openbank.party.application.port.`in`.ErasePartyCommand
import com.openbank.party.application.port.`in`.ResolvePartyByRcCommand
import com.openbank.party.application.port.`in`.SelfRegisterPartyCommand
import com.openbank.party.application.port.`in`.UpdatePartyCommand
import com.openbank.party.application.port.`in`.UploadDocumentCommand
import com.openbank.party.domain.model.Address
import com.openbank.party.domain.model.AmlStatus
import com.openbank.party.domain.model.DocumentType
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyClassification
import com.openbank.party.domain.model.PartyDocument
import com.openbank.party.domain.model.PartyDocumentFile
import com.openbank.party.domain.model.PartyEvent
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class PartyServiceTest {

    private val now = Instant.parse("2025-01-01T00:00:00Z")
    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val otherPartyId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    // JUnit 5's default PER_METHOD lifecycle gives every test a fresh instance, so one slot per
    // class is one slot per test. Captures the PartyEvent handed to the repository — the event now
    // travels IN the state-change transaction (#4007), so the repository call is where to assert it.
    private val eventSlot = slot<PartyEvent>()

    private fun assertEvent(type: String, aggregateId: UUID) {
        assertThat(eventSlot.captured.eventType).isEqualTo(type)
        assertThat(eventSlot.captured.aggregateId).isEqualTo(aggregateId)
    }

    @Test
    fun `createParty saves and publishes event`(): Unit = runBlocking {
        val service = newService()

        val savedPartySlot = slot<Party>()
        coEvery { service.partyRepo.findByEmail("alice@example.com") } returns null
        coEvery { service.partyRepo.save(capture(savedPartySlot), capture(eventSlot)) } answers
            { savedPartySlot.captured }

        val result = service.createParty(
            CreatePartyCommand(
                idempotencyKey = "idem-1",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Alice Example",
                tradingName = null,
                dateOfBirth = "1990-01-01",
                nationality = "CZ",
                taxId = "TAX-1",
                registrationNumber = null,
                email = "alice@example.com",
                phone = "+420123456789",
                address = Address("Line 1", null, "Prague", "11000", "CZ"),
            ),
        )

        assertThat(savedPartySlot.captured.email).isEqualTo("alice@example.com")
        assertThat(savedPartySlot.captured.status).isEqualTo(PartyStatus.PENDING_KYC)
        assertThat(savedPartySlot.captured.classification).isEqualTo(PartyClassification.CUSTOMER)
        assertThat(savedPartySlot.captured.kycStatus).isEqualTo(KycStatus.NOT_STARTED)
        assertThat(result).isSameAs(savedPartySlot.captured)
        assertEvent("PARTY_CREATED", result.id)
        verify(exactly = 1) { service.metrics.partyCreated("INDIVIDUAL") }
    }

    @Test
    fun `updateKycStatus verifying transition into ACTIVE counts partyVerified once`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(
            status = PartyStatus.PENDING_KYC,
            kycStatus = KycStatus.IN_PROGRESS,
            amlStatus = AmlStatus.CLEARED,
        )
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        service.updateKycStatus(original.id, KycStatus.APPROVED)

        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.ACTIVE)
        verify(exactly = 1) { service.metrics.partyVerified("INDIVIDUAL") }
    }

    @Test
    fun `updateKycStatus rejection does not count partyVerified`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(status = PartyStatus.PENDING_KYC, kycStatus = KycStatus.IN_PROGRESS)
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        service.updateKycStatus(original.id, KycStatus.REJECTED)

        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.SUSPENDED)
        verify(exactly = 0) { service.metrics.partyVerified(any()) }
    }

    @Test
    fun `createParty throws PartyAlreadyExistsException when email exists`(): Unit = runBlocking {
        val service = newService()

        coEvery { service.partyRepo.findByEmail("alice@example.com") } returns sampleParty()

        assertThatThrownBy {
            runBlocking {
                service.createParty(
                    CreatePartyCommand(
                        idempotencyKey = "idem-1",
                        partyType = PartyType.INDIVIDUAL,
                        legalName = "Alice Example",
                        tradingName = null,
                        dateOfBirth = null,
                        nationality = null,
                        taxId = null,
                        registrationNumber = null,
                        email = "alice@example.com",
                        phone = null,
                        address = null,
                    ),
                )
            }
        }.isInstanceOf(PartyAlreadyExistsException::class.java)
    }

    @Test
    fun `getParty returns party from repo`(): Unit = runBlocking {
        val service = newService()
        val party = sampleParty()
        coEvery { service.partyRepo.findById(party.id) } returns party

        val result = service.getParty(party.id)

        assertThat(result).isEqualTo(party)
    }

    @Test
    fun `getParty throws PartyNotFoundException when not found`() {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns null

        assertThatThrownBy {
            runBlocking { service.getParty(partyId) }
        }.isInstanceOf(PartyNotFoundException::class.java)
    }

    @Test
    fun `exportPartyData returns the subject and document metadata stamped with the clock`(): Unit = runBlocking {
        val service = newService()
        val party = sampleParty()
        val doc = PartyDocument(
            id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            partyId = party.id,
            documentType = DocumentType.NATIONAL_ID,
            documentNumber = "990101/1234",
            issuingCountry = "CZ",
            expiryDate = "2030-01-01",
            verifiedAt = now,
            createdAt = now,
        )
        coEvery { service.partyRepo.findById(party.id) } returns party
        coEvery { service.documentRepo.findByPartyId(party.id) } returns listOf(doc)

        val export = service.exportPartyData(party.id)

        assertThat(export.party).isEqualTo(party)
        assertThat(export.documents).containsExactly(doc)
        assertThat(export.exportedAt).isEqualTo(now)
    }

    @Test
    fun `exportPartyData throws PartyNotFoundException when not found`() {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns null

        assertThatThrownBy {
            runBlocking { service.exportPartyData(partyId) }
        }.isInstanceOf(PartyNotFoundException::class.java)
    }

    @Test
    fun `updateParty updates only provided fields`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty()
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        val result = service.updateParty(
            UpdatePartyCommand(
                id = original.id,
                email = "new@example.com",
                phone = null,
                address = null,
                tradingName = "New Trade",
            ),
        )

        assertThat(updatedSlot.captured.email).isEqualTo("new@example.com")
        assertThat(updatedSlot.captured.phone).isEqualTo(original.phone)
        assertThat(updatedSlot.captured.address).isEqualTo(original.address)
        assertThat(updatedSlot.captured.tradingName).isEqualTo("New Trade")
        assertThat(updatedSlot.captured.legalName).isEqualTo(original.legalName)
        assertThat(updatedSlot.captured.status).isEqualTo(original.status)
        assertThat(result).isSameAs(updatedSlot.captured)
        assertEvent("PARTY_UPDATED", result.id)
    }

    @Test
    fun `addDocument throws PartyNotFoundException when party missing`() {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.addDocument(
                    AddDocumentCommand(
                        partyId = partyId,
                        documentType = DocumentType.PASSPORT,
                        documentNumber = "P1234567",
                        issuingCountry = "CZ",
                        expiryDate = null,
                    ),
                )
            }
        }.isInstanceOf(PartyNotFoundException::class.java)
    }

    @Test
    fun `addDocument saves document`(): Unit = runBlocking {
        val service = newService()
        val documentSlot = slot<PartyDocument>()
        coEvery { service.partyRepo.findById(partyId) } returns sampleParty(id = partyId)
        coEvery { service.documentRepo.save(capture(documentSlot)) } answers { documentSlot.captured }

        val result = service.addDocument(
            AddDocumentCommand(
                partyId = partyId,
                documentType = DocumentType.PASSPORT,
                documentNumber = "P1234567",
                issuingCountry = "CZ",
                expiryDate = "2030-01-01",
            ),
        )

        assertThat(documentSlot.captured.partyId).isEqualTo(partyId)
        assertThat(documentSlot.captured.documentType).isEqualTo(DocumentType.PASSPORT)
        assertThat(documentSlot.captured.documentNumber).isEqualTo("P1234567")
        assertThat(documentSlot.captured.issuingCountry).isEqualTo("CZ")
        assertThat(documentSlot.captured.expiryDate).isEqualTo("2030-01-01")
        assertThat(result).isSameAs(documentSlot.captured)
    }

    @Test
    fun `updateKycStatus sets ACTIVE only when AML already CLEARED (two-key gate)`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(
            status = PartyStatus.PENDING_KYC,
            kycStatus = KycStatus.IN_PROGRESS,
            amlStatus = AmlStatus.CLEARED,
        )
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        val result = service.updateKycStatus(original.id, KycStatus.APPROVED)

        assertThat(updatedSlot.captured.kycStatus).isEqualTo(KycStatus.APPROVED)
        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.ACTIVE)
        assertThat(result).isSameAs(updatedSlot.captured)
        assertEvent("KYC_STATUS_CHANGED", result.id)
    }

    @Test
    fun `updateKycStatus stays PENDING_KYC when AML not yet cleared`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(
            status = PartyStatus.PENDING_KYC,
            kycStatus = KycStatus.IN_PROGRESS,
            amlStatus = AmlStatus.NOT_SCREENED,
        )
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        service.updateKycStatus(original.id, KycStatus.APPROVED)

        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.PENDING_KYC)
    }

    @Test
    fun `updateAmlStatus activates when KYC already APPROVED`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(
            status = PartyStatus.PENDING_KYC,
            kycStatus = KycStatus.APPROVED,
            amlStatus = AmlStatus.NOT_SCREENED,
        )
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        service.updateAmlStatus(original.id, AmlStatus.CLEARED)

        assertThat(updatedSlot.captured.amlStatus).isEqualTo(AmlStatus.CLEARED)
        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.ACTIVE)
    }

    @Test
    fun `updateAmlStatus BLOCKED suspends the party`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(
            status = PartyStatus.PENDING_KYC,
            kycStatus = KycStatus.APPROVED,
            amlStatus = AmlStatus.NOT_SCREENED,
        )
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        service.updateAmlStatus(original.id, AmlStatus.BLOCKED)

        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.SUSPENDED)
    }

    @Test
    fun `updateKycStatus sets SUSPENDED when REJECTED`(): Unit = runBlocking {
        val service = newService()
        val original = sampleParty(status = PartyStatus.ACTIVE, kycStatus = KycStatus.IN_PROGRESS)
        val updatedSlot = slot<Party>()
        coEvery { service.partyRepo.findById(original.id) } returns original
        coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers { updatedSlot.captured }

        val result = service.updateKycStatus(original.id, KycStatus.REJECTED)

        assertThat(updatedSlot.captured.kycStatus).isEqualTo(KycStatus.REJECTED)
        assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.SUSPENDED)
        assertThat(result).isSameAs(updatedSlot.captured)
        assertEvent("KYC_STATUS_CHANGED", result.id)
    }

    @Test
    fun `updateKycStatus sets SUSPENDED when EXPIRED (AbandonedRegistrationCleaner's daily sweep)`(): Unit =
        runBlocking {
            // Was silently falling through to PENDING_KYC — the cleanup job's whole point (mark
            // a stuck registration as blocked) didn't actually take effect, issue #468.
            val service = newService()
            val original = sampleParty(status = PartyStatus.PENDING_KYC, kycStatus = KycStatus.IN_PROGRESS)
            val updatedSlot = slot<Party>()
            coEvery { service.partyRepo.findById(original.id) } returns original
            coEvery { service.partyRepo.update(capture(updatedSlot), capture(eventSlot)) } answers
                { updatedSlot.captured }

            val result = service.updateKycStatus(original.id, KycStatus.EXPIRED)

            assertThat(updatedSlot.captured.kycStatus).isEqualTo(KycStatus.EXPIRED)
            assertThat(updatedSlot.captured.status).isEqualTo(PartyStatus.SUSPENDED)
            assertThat(result).isSameAs(updatedSlot.captured)
        }

    @Test
    fun `listParties with status filter dispatches to listByStatus and countByStatus`(): Unit = runBlocking {
        val service = newService()
        val parties = listOf(sampleParty(status = PartyStatus.PENDING_KYC))
        coEvery { service.partyRepo.listByStatus(PartyStatus.PENDING_KYC, 0, 20) } returns parties
        coEvery { service.partyRepo.countByStatus(PartyStatus.PENDING_KYC) } returns 1L

        val result = service.listParties(0, 20, PartyStatus.PENDING_KYC)

        assertThat(result["items"] as List<*>).hasSize(1)
        assertThat(result["total"]).isEqualTo(1L)
        assertThat(result["statusFilter"]).isEqualTo("PENDING_KYC")
        coVerify(exactly = 1) { service.partyRepo.listByStatus(PartyStatus.PENDING_KYC, 0, 20) }
        coVerify(exactly = 1) { service.partyRepo.countByStatus(PartyStatus.PENDING_KYC) }
        coVerify(exactly = 0) { service.partyRepo.listAll(any(), any()) }
        coVerify(exactly = 0) { service.partyRepo.countAll() }
    }

    @Test
    fun `listParties without status dispatches to listAll and countAll`(): Unit = runBlocking {
        val service = newService()
        val parties = listOf(sampleParty(), sampleParty(id = otherPartyId))
        coEvery { service.partyRepo.listAll(0, 20) } returns parties
        coEvery { service.partyRepo.countAll() } returns 2L

        val result = service.listParties(0, 20, null)

        assertThat(result["items"] as List<*>).hasSize(2)
        assertThat(result["total"]).isEqualTo(2L)
        assertThat(result).doesNotContainKey("statusFilter")
        coVerify(exactly = 1) { service.partyRepo.listAll(0, 20) }
        coVerify(exactly = 1) { service.partyRepo.countAll() }
        coVerify(exactly = 0) { service.partyRepo.listByStatus(any(), any(), any()) }
    }

    // ── Mobile self-registration tests (Sprint 1) ──────────────────────────────

    @Test
    fun `selfRegisterParty creates new party and publishes event when sub is unknown`(): Unit = runBlocking {
        val service = newService()
        val sub = "keycloak-sub-001"
        val savedSlot = slot<Party>()
        coEvery { service.partyRepo.findByKeycloakSub(sub) } returns null
        coEvery { service.partyRepo.save(capture(savedSlot), capture(eventSlot)) } answers { savedSlot.captured }

        val (party, isNew) = service.selfRegisterParty(
            SelfRegisterPartyCommand(
                keycloakSub = sub, emailVerified = true, partyType = PartyType.INDIVIDUAL,
                legalName = "Test User", email = "test@example.com",
                phone = null, dateOfBirth = null, nationality = null, address = null,
            ),
        )

        assertThat(isNew).isTrue()
        assertThat(party.keycloakSub).isEqualTo(sub)
        assertThat(party.status).isEqualTo(PartyStatus.PENDING_KYC)
        assertThat(eventSlot.captured.eventType).isEqualTo("PARTY_CREATED")
    }

    @Test
    fun `selfRegisterParty returns existing party without saving when sub already registered`(): Unit = runBlocking {
        val service = newService()
        val sub = "keycloak-sub-existing"
        val existing = sampleParty().copy(keycloakSub = sub)
        coEvery { service.partyRepo.findByKeycloakSub(sub) } returns existing

        val (party, isNew) = service.selfRegisterParty(
            SelfRegisterPartyCommand(
                keycloakSub = sub, emailVerified = true, partyType = PartyType.INDIVIDUAL,
                legalName = "Test User", email = "test@example.com",
                phone = null, dateOfBirth = null, nationality = null, address = null,
            ),
        )

        assertThat(isNew).isFalse()
        assertThat(party).isEqualTo(existing)
        coVerify(exactly = 0) { service.partyRepo.save(any()) }
        coVerify(exactly = 0) { service.partyRepo.save(any(), any()) }
    }

    @Test
    fun `getMyParty returns null when sub has no party`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findByKeycloakSub("unknown-sub") } returns null

        val result = service.getMyParty("unknown-sub")

        assertThat(result).isNull()
    }

    @Test
    fun `getMyParty returns party for known sub`(): Unit = runBlocking {
        val service = newService()
        val party = sampleParty().copy(keycloakSub = "sub-123")
        coEvery { service.partyRepo.findByKeycloakSub("sub-123") } returns party

        val result = service.getMyParty("sub-123")

        assertThat(result).isEqualTo(party)
    }

    @Test
    fun `uploadDocument saves file and returns it`(): Unit = runBlocking {
        val service = newService()
        val content = byteArrayOf(1, 2, 3)
        val fileSlot = slot<PartyDocumentFile>()
        coEvery { service.partyRepo.findById(partyId) } returns sampleParty(id = partyId)
        coEvery { service.documentFileRepo.save(capture(fileSlot)) } answers { fileSlot.captured }

        val result = service.uploadDocument(
            UploadDocumentCommand(
                partyId = partyId,
                documentType = DocumentType.PASSPORT,
                fileName = "passport.jpg",
                mimeType = "image/jpeg",
                content = content,
            ),
        )

        assertThat(fileSlot.captured.partyId).isEqualTo(partyId)
        assertThat(fileSlot.captured.documentType).isEqualTo(DocumentType.PASSPORT)
        assertThat(fileSlot.captured.mimeType).isEqualTo("image/jpeg")
        assertThat(result).isSameAs(fileSlot.captured)
    }

    @Test
    fun `uploadDocument throws PartyNotFoundException when party does not exist`() {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.uploadDocument(
                    UploadDocumentCommand(
                        partyId = partyId,
                        documentType = DocumentType.PASSPORT,
                        fileName = null,
                        mimeType = "image/jpeg",
                        content = byteArrayOf(),
                    ),
                )
            }
        }.isInstanceOf(PartyNotFoundException::class.java)
    }

    // ─── eraseParty (GDPR Art. 17) ──────────────────────────────────────────

    @Test
    fun `eraseParty deletes document files before anonymizing party (GDPR Art 17)`(): Unit = runBlocking {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns sampleParty(id = partyId)
        coJustRun { service.documentFileRepo.deleteByPartyId(partyId) }
        coJustRun { service.partyRepo.anonymize(partyId, capture(eventSlot)) }

        service.eraseParty(ErasePartyCommand(partyId))

        // Files must be deleted before anonymize — verify both were called.
        coVerify(exactly = 1) { service.documentFileRepo.deleteByPartyId(partyId) }
        coVerify(exactly = 1) { service.partyRepo.anonymize(partyId, any()) }
        assertEvent("PARTY_ERASED", partyId)
    }

    @Test
    fun `eraseParty throws PartyNotFoundException when party does not exist`() {
        val service = newService()
        coEvery { service.partyRepo.findById(partyId) } returns null

        assertThatThrownBy {
            runBlocking { service.eraseParty(ErasePartyCommand(partyId)) }
        }.isInstanceOf(PartyNotFoundException::class.java)

        coVerify(exactly = 0) { service.documentFileRepo.deleteByPartyId(any()) }
        coVerify(exactly = 0) { service.partyRepo.anonymize(any()) }
    }

    // ─── getDocumentContent ─────────────────────────────────────────────────

    @Test
    fun `getDocumentContent returns file when found by fileId and partyId`(): Unit = runBlocking {
        val service = newService()
        val fileId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val file = PartyDocumentFile(
            id = fileId,
            partyId = partyId,
            documentType = DocumentType.PASSPORT,
            fileName = "passport.jpg",
            mimeType = "image/jpeg",
            content = byteArrayOf(1, 2, 3),
            uploadedAt = now,
        )
        coEvery { service.documentFileRepo.findByIdAndPartyId(fileId, partyId) } returns file

        val result = service.getDocumentContent(partyId = partyId, fileId = fileId)

        assertThat(result).isEqualTo(file)
    }

    @Test
    fun `getDocumentContent returns null when file not found or belongs to different party`(): Unit = runBlocking {
        val service = newService()
        val fileId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        coEvery { service.documentFileRepo.findByIdAndPartyId(fileId, partyId) } returns null

        val result = service.getDocumentContent(partyId = partyId, fileId = fileId)

        assertThat(result).isNull()
    }

    // ─── resolvePartyByRc + isDedupAvailable (ADR-0072) ──────────────────────

    @Test
    fun `isDedupAvailable returns false when pepper is absent`(): Unit = runBlocking {
        val service = newService() // rcPepper = Optional.empty()
        assertThat(service.isDedupAvailable()).isFalse()
    }

    @Test
    fun `isDedupAvailable returns false when pepper is blank`(): Unit = runBlocking {
        val service = newService().also { it.rcPepper = Optional.of("   ") }
        assertThat(service.isDedupAvailable()).isFalse()
    }

    @Test
    fun `isDedupAvailable returns true when pepper is set`(): Unit = runBlocking {
        val service = newService().also { it.rcPepper = Optional.of("secret-pepper") }
        assertThat(service.isDedupAvailable()).isTrue()
    }

    @Test
    fun `resolvePartyByRc returns null when pepper absent (dedup off)`(): Unit = runBlocking {
        val service = newService() // rcPepper = Optional.empty()
        val result = service.resolvePartyByRc(
            ResolvePartyByRcCommand("7601010032"),
        )
        assertThat(result).isNull()
        coVerify(exactly = 0) { service.partyRepo.findByRcBlindIndex(any()) }
    }

    @Test
    fun `resolvePartyByRc returns null when RC is syntactically invalid`(): Unit = runBlocking {
        val service = newService().also { it.rcPepper = Optional.of("pepper") }
        val result = service.resolvePartyByRc(
            ResolvePartyByRcCommand("not-an-rc"),
        )
        assertThat(result).isNull()
        coVerify(exactly = 0) { service.partyRepo.findByRcBlindIndex(any()) }
    }

    @Test
    fun `resolvePartyByRc returns party when blind index matches`(): Unit = runBlocking {
        val service = newService().also { it.rcPepper = Optional.of("pepper") }
        val party = sampleParty()
        coEvery { service.partyRepo.findByRcBlindIndex(any()) } returns party

        val result = service.resolvePartyByRc(
            ResolvePartyByRcCommand("7601010032"),
        )

        assertThat(result).isEqualTo(party)
        coVerify(exactly = 1) { service.partyRepo.findByRcBlindIndex(any()) }
    }

    @Test
    fun `resolvePartyByRc returns null when no party matches blind index`(): Unit = runBlocking {
        val service = newService().also { it.rcPepper = Optional.of("pepper") }
        coEvery { service.partyRepo.findByRcBlindIndex(any()) } returns null

        val result = service.resolvePartyByRc(
            ResolvePartyByRcCommand("7601010032"),
        )

        assertThat(result).isNull()
    }

    @Test
    fun `createParty stores rcBlindIndex when pepper configured and valid Czech RC supplied`(): Unit = runBlocking {
        val service = newService().also { it.rcPepper = Optional.of("pepper") }
        val savedSlot = slot<Party>()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        coEvery { service.partyRepo.save(capture(savedSlot), capture(eventSlot)) } answers { savedSlot.captured }

        service.createParty(
            CreatePartyCommand(
                idempotencyKey = "key",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Test",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = "7601010032",
                registrationNumber = null,
                email = "t@example.com",
                phone = null,
                address = null,
            ),
        )

        assertThat(savedSlot.captured.rcBlindIndex).isNotNull()
        assertThat(savedSlot.captured.rcIndexKeyVersion).isEqualTo(1)
    }

    @Test
    fun `createParty leaves rcBlindIndex null when pepper absent`(): Unit = runBlocking {
        val service = newService() // no pepper
        val savedSlot = slot<Party>()
        coEvery { service.partyRepo.findByEmail(any()) } returns null
        coEvery { service.partyRepo.save(capture(savedSlot), capture(eventSlot)) } answers { savedSlot.captured }

        service.createParty(
            CreatePartyCommand(
                idempotencyKey = "key2",
                partyType = PartyType.INDIVIDUAL,
                legalName = "Test2",
                tradingName = null,
                dateOfBirth = null,
                nationality = null,
                taxId = "7601010032",
                registrationNumber = null,
                email = "t2@example.com",
                phone = null,
                address = null,
            ),
        )

        assertThat(savedSlot.captured.rcBlindIndex).isNull()
        assertThat(savedSlot.captured.rcIndexKeyVersion).isNull()
    }

    private fun newService() = PartyService().apply {
        partyRepo = mockk()
        documentRepo = mockk()
        documentFileRepo = mockk()
        metrics = mockk(relaxed = true)
        changeMetrics = mockk(relaxed = true)
        gdprAggregation = mockk(relaxed = true)
        rcPepper = Optional.empty()
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    private fun sampleParty(
        id: UUID = partyId,
        status: PartyStatus = PartyStatus.PENDING_KYC,
        kycStatus: KycStatus = KycStatus.NOT_STARTED,
        amlStatus: AmlStatus = AmlStatus.NOT_SCREENED,
    ) = Party(
        id = id,
        partyType = PartyType.INDIVIDUAL,
        status = status,
        legalName = "Alice Example",
        tradingName = "Alice Co",
        dateOfBirth = "1990-01-01",
        nationality = "CZ",
        taxId = "TAX-1",
        registrationNumber = null,
        email = "alice@example.com",
        phone = "+420123456789",
        address = Address("Line 1", null, "Prague", "11000", "CZ"),
        kycStatus = kycStatus,
        createdAt = now,
        updatedAt = now,
        amlStatus = amlStatus,
    )
}
