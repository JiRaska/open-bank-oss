// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.out.CeremonyRepositoryPort
import com.openbank.document.application.port.out.ClientSignatureIssuerPort
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.SignatureSealPort
import com.openbank.document.application.port.out.SignerVerificationPort
import com.openbank.document.domain.model.CeremonyStatus
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import com.openbank.document.domain.model.Signer
import com.openbank.document.domain.model.SignerStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.storage.ObjectStorePort
import com.openbank.libs.testing.audit.AuditEventTime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * ADR-0162 D4 continued: a SIGNED decision applies the signer's own one-time signature
 * ([ClientSignatureIssuerPort]) BEFORE persisting the decision; the bank's seal
 * ([SignatureSealPort]) applies only once, LAST, after every signer reaches a terminal decision.
 */
class SignatureCeremonyServiceTest {

    private val ceremonyRepo: CeremonyRepositoryPort = mockk()
    private val documentRepo: DocumentRepositoryPort = mockk()
    private val objectStore: ObjectStorePort = mockk()
    private val sealPort: SignatureSealPort = mockk()
    private val clientSignaturePort: ClientSignatureIssuerPort = mockk()
    private val signerVerificationPort: SignerVerificationPort = mockk()
    private val service = SignatureCeremonyService(
        ceremonyRepo = ceremonyRepo,
        documentRepo = documentRepo,
        objectStore = objectStore,
        sealPort = sealPort,
        clientSignaturePort = clientSignaturePort,
        signerVerificationPort = signerVerificationPort,
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        // See DocumentRenderServiceTest for why the timestamps feature must be disabled here: a
        // hand-built mapper that keeps it on serialises Instants as epoch numbers, which
        // AuditConsumer's Instant.parse cannot read (#3914).
        objectMapper = ObjectMapper().registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
    )

    @Test
    fun `a single-signer ceremony applies the client signature then the bank seal`(): Unit = runBlocking {
        val ceremony = ceremony(listOf(signer("party-1")))
        val document = document()
        val pdf = "pdf-bytes".toByteArray()
        val clientSigned = "client-signed".toByteArray()
        val sealed = "sealed".toByteArray()
        coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony
        coEvery { documentRepo.findById(ceremony.documentId) } returns document
        coEvery {
            signerVerificationPort.verify("party-1", "evidence-1", document.sha256, ceremony.id.toString())
        } returns
            true
        // First get() (inside signAsClient) sees the original bytes; the second (inside
        // sealDocument) sees what signAsClient just put -- simulating the object store's own
        // read-your-writes behavior across the two sequential steps.
        coEvery { objectStore.get(document.storageKey) } returns pdf andThen clientSigned
        coEvery { clientSignaturePort.signAsClient(pdf, "party-1", any()) } returns clientSigned
        coEvery { objectStore.put(document.storageKey, clientSigned, document.contentType) } returns Unit
        coEvery { sealPort.sealPades(clientSigned, any()) } returns sealed
        coEvery { objectStore.put(document.storageKey, sealed, document.contentType) } returns Unit
        coEvery { ceremonyRepo.saveWithOutbox(any(), any()) } answers { firstArg() }
        coEvery { documentRepo.save(any()) } answers { firstArg() }

        val result = service.recordDecision(ceremony.id, "party-1", SignerStatus.SIGNED, "evidence-1")

        assertThat(result.status).isEqualTo(CeremonyStatus.COMPLETED)
        coVerifyOrder {
            clientSignaturePort.signAsClient(pdf, "party-1", any())
            sealPort.sealPades(clientSigned, any())
        }
        // The bug this pins: a completed ceremony used to seal the PDF bytes but never persist
        // the document's own status past PENDING_SIGNATURE, so
        // OnboardingDocumentService.ensureOnboardingAgreement's "already signed" check could
        // never match and every subsequent login re-ran the full sign ceremony.
        coVerify { documentRepo.save(match { it.status == DocumentStatus.SIGNED }) }
    }

    /**
     * #3914: red before the `at` -> `occurredAt` rename — the ceremony-completion instant was in the
     * payload under a name `AuditConsumer` does not read, so the audit row for the completion of a
     * qualified signature ceremony recorded the consumer's ingest clock as the signing time.
     */
    @Test
    fun `the SignatureCeremonyCompleted payload carries the completion instant as the audit event time`(): Unit =
        runBlocking {
            val ceremony = ceremony(listOf(signer("party-1")))
            val document = document()
            val pdf = "pdf-bytes".toByteArray()
            val clientSigned = "client-signed".toByteArray()
            coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony
            coEvery { documentRepo.findById(ceremony.documentId) } returns document
            coEvery {
                signerVerificationPort.verify("party-1", "evidence-1", document.sha256, ceremony.id.toString())
            } returns true
            coEvery { objectStore.get(document.storageKey) } returns pdf andThen clientSigned
            coEvery { clientSignaturePort.signAsClient(pdf, "party-1", any()) } returns clientSigned
            coEvery { objectStore.put(document.storageKey, any(), document.contentType) } returns Unit
            coEvery { sealPort.sealPades(clientSigned, any()) } returns "sealed".toByteArray()
            val savedMsg = slot<OutboxMessage>()
            coEvery { ceremonyRepo.saveWithOutbox(any(), capture(savedMsg)) } answers { firstArg() }
            coEvery { documentRepo.save(any()) } answers { firstArg() }

            service.recordDecision(ceremony.id, "party-1", SignerStatus.SIGNED, "evidence-1")

            AuditEventTime.assertRecordedAsEventTime(savedMsg.captured.payload, FIXED_NOW)
        }

    @Test
    fun `sealing self-heals a document whose ceremony was opened before the pending-signature fix`(): Unit =
        runBlocking {
            // Simulates a ceremony that reached DRAFT under the pre-fix openCeremony(), which
            // never advanced the document past GENERATED.
            val ceremony = ceremony(listOf(signer("party-1")))
            val document = document().copy(status = DocumentStatus.GENERATED)
            val pdf = "pdf-bytes".toByteArray()
            coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony
            coEvery { documentRepo.findById(ceremony.documentId) } returns document
            coEvery {
                signerVerificationPort.verify("party-1", "evidence-1", document.sha256, ceremony.id.toString())
            } returns true
            coEvery { objectStore.get(document.storageKey) } returns pdf
            coEvery { clientSignaturePort.signAsClient(any(), "party-1", any()) } returns pdf
            coEvery { objectStore.put(any(), any(), any()) } returns Unit
            coEvery { sealPort.sealPades(any(), any()) } returns pdf
            coEvery { ceremonyRepo.saveWithOutbox(any(), any()) } answers { firstArg() }
            coEvery { documentRepo.save(any()) } answers { firstArg() }

            service.recordDecision(ceremony.id, "party-1", SignerStatus.SIGNED, "evidence-1")

            coVerify { documentRepo.save(match { it.status == DocumentStatus.SIGNED }) }
        }

    @Test
    fun `opening a ceremony advances a GENERATED document to PENDING_SIGNATURE`(): Unit = runBlocking {
        val document = document().copy(status = DocumentStatus.GENERATED)
        coEvery { documentRepo.findById(document.id) } returns document
        coEvery { documentRepo.save(any()) } answers { firstArg() }
        coEvery { ceremonyRepo.save(any()) } answers { firstArg() }

        service.openCeremony(OpenCeremonyCommand(document.id, listOf("party-1"), SignatureLevel.ADVANCED))

        coVerify { documentRepo.save(match { it.status == DocumentStatus.PENDING_SIGNATURE }) }
    }

    @Test
    fun `opening a ceremony against an already-PENDING_SIGNATURE document does not re-save it`(): Unit = runBlocking {
        // A retry after DuplicateCeremonyException (ADR-0162 D7 self-heal) can call this again
        // for a document a prior attempt already advanced -- markPendingSignature() requires
        // GENERATED, so a blind re-call would throw.
        val document = document().copy(status = DocumentStatus.PENDING_SIGNATURE)
        coEvery { documentRepo.findById(document.id) } returns document
        coEvery { ceremonyRepo.save(any()) } answers { firstArg() }

        service.openCeremony(OpenCeremonyCommand(document.id, listOf("party-1"), SignatureLevel.ADVANCED))

        coVerify(exactly = 0) { documentRepo.save(any()) }
    }

    @Test
    fun `a two-signer ceremony signs each signer without sealing until the last one`(): Unit = runBlocking {
        val ceremony = ceremony(listOf(signer("party-1", order = 1), signer("party-2", order = 2)))
        val document = document()
        val pdf = "pdf-bytes".toByteArray()
        coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony
        coEvery { documentRepo.findById(ceremony.documentId) } returns document
        coEvery {
            signerVerificationPort.verify("party-1", "evidence-1", document.sha256, ceremony.id.toString())
        } returns
            true
        coEvery { objectStore.get(document.storageKey) } returns pdf
        coEvery { clientSignaturePort.signAsClient(any(), "party-1", any()) } returns pdf
        coEvery { objectStore.put(any(), any(), any()) } returns Unit
        coEvery { ceremonyRepo.save(any()) } answers { firstArg() }

        val result = service.recordDecision(ceremony.id, "party-1", SignerStatus.SIGNED, "evidence-1")

        assertThat(result.status).isEqualTo(CeremonyStatus.PARTIALLY_SIGNED)
        coVerify(exactly = 1) { clientSignaturePort.signAsClient(any(), "party-1", any()) }
        coVerify(exactly = 0) { sealPort.sealPades(any(), any()) }
        coVerify(exactly = 0) { ceremonyRepo.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `a DECLINED decision applies neither the client signature nor the seal`(): Unit = runBlocking {
        val ceremony = ceremony(listOf(signer("party-1")))
        coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony
        coEvery { ceremonyRepo.save(any()) } answers { firstArg() }

        val result = service.recordDecision(ceremony.id, "party-1", SignerStatus.DECLINED, null)

        assertThat(result.status).isEqualTo(CeremonyStatus.DECLINED)
        coVerify(exactly = 0) { clientSignaturePort.signAsClient(any(), any(), any()) }
        coVerify(exactly = 0) { sealPort.sealPades(any(), any()) }
    }

    @Test
    fun `a failed SCA verification never applies the client signature`(): Unit = runBlocking {
        val ceremony = ceremony(listOf(signer("party-1")))
        val document = document()
        coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony
        coEvery { documentRepo.findById(ceremony.documentId) } returns document
        coEvery {
            signerVerificationPort.verify("party-1", "bad-evidence", document.sha256, ceremony.id.toString())
        } returns false

        assertThatThrownBy {
            runBlocking { service.recordDecision(ceremony.id, "party-1", SignerStatus.SIGNED, "bad-evidence") }
        }
            .isInstanceOf(IllegalStateException::class.java)

        coVerify(exactly = 0) { clientSignaturePort.signAsClient(any(), any(), any()) }
    }

    @Test
    fun `an out-of-order SIGNED decision is rejected before any client signature is applied`(): Unit = runBlocking {
        // party-2 (order 2) tries to sign before party-1: the domain rejects it, and because
        // validation now runs BEFORE signing, no phantom signature is written (nor is SCA even
        // consulted). On the pre-fix ordering this same call signed the document, then threw.
        val ceremony = ceremony(listOf(signer("party-1", order = 1), signer("party-2", order = 2)))
        coEvery { ceremonyRepo.findById(ceremony.id) } returns ceremony

        assertThatThrownBy {
            runBlocking { service.recordDecision(ceremony.id, "party-2", SignerStatus.SIGNED, "evidence-2") }
        }.isInstanceOf(IllegalArgumentException::class.java)

        coVerify(exactly = 0) { clientSignaturePort.signAsClient(any(), any(), any()) }
        coVerify(exactly = 0) { signerVerificationPort.verify(any(), any(), any(), any()) }
    }

    @Test
    fun `replaying a decision this signer already recorded absorbs it instead of failing`(): Unit = runBlocking {
        // The deadlock this guards, seen live: the caller retries a decision whose HTTP response
        // was lost, the ceremony behind it is already COMPLETED, and the domain rejects every
        // decision on a terminal ceremony -- so the retry failed forever and the client sat on the
        // signing screen unable to sign a contract they had ALREADY signed. Absorbing the replay
        // must not re-execute the signature act: the SCA evidence is single-use and each signing
        // act mints a fresh one-time certificate, so re-running it is not a no-op.
        val signed = Signer(partyRef = "party-1", order = 1, status = SignerStatus.SIGNED, signedAt = FIXED_NOW)
        val completed = ceremony(listOf(signed)).copy(status = CeremonyStatus.COMPLETED)
        coEvery { ceremonyRepo.findById(completed.id) } returns completed

        val result = service.recordDecision(completed.id, "party-1", SignerStatus.SIGNED, "evidence-1")

        assertThat(result.status).isEqualTo(CeremonyStatus.COMPLETED)
        coVerify(exactly = 0) { signerVerificationPort.verify(any(), any(), any(), any()) }
        coVerify(exactly = 0) { clientSignaturePort.signAsClient(any(), any(), any()) }
        coVerify(exactly = 0) { sealPort.sealPades(any(), any()) }
        coVerify(exactly = 0) { ceremonyRepo.save(any()) }
        coVerify(exactly = 0) { ceremonyRepo.saveWithOutbox(any(), any()) }
    }

    private fun signer(partyRef: String, order: Int = 1) =
        Signer(partyRef = partyRef, order = order, status = SignerStatus.PENDING, signedAt = null)

    private fun ceremony(signers: List<Signer>) = SignatureCeremony(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        signers = signers,
        status = CeremonyStatus.PENDING,
        signatureLevel = SignatureLevel.ADVANCED,
        createdAt = FIXED_NOW,
    )

    private fun document() = Document(
        id = UUID.randomUUID(),
        templateCode = "VOP_CS",
        templateVersion = "1.1.0",
        sha256 = "abc",
        storageKey = "documents/1",
        contentType = "application/pdf",
        sizeBytes = 10,
        status = DocumentStatus.PENDING_SIGNATURE,
        metadata = emptyMap(),
        partyRef = "party-1",
        caseRef = null,
        productRef = null,
        retainUntil = null,
        createdAt = FIXED_NOW,
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")
    }
}
