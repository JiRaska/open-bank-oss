// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.DocumentQueryUseCase
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.IssueOnboardingDocumentCommand
import com.openbank.document.application.port.`in`.OpenCeremonyCommand
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.`in`.SignatureCeremonyUseCase
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.DuplicateCeremonyException
import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.SignatureCeremony
import com.openbank.document.domain.model.SignatureLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0162 D7 onboarding wiring: idempotent + resumable under at-least-once Kafka delivery. The
 * idempotency key is keyed on the account, the document is rendered at most once for it, and the
 * ceremony is opened only if the document doesn't already have one (so a crash between the two
 * effects self-heals on replay).
 */
class OnboardingDocumentServiceTest {

    private val productCatalogPort: ProductCatalogPort = mockk()
    private val renderUseCase: DocumentRenderUseCase = mockk()
    private val documentQueryUseCase: DocumentQueryUseCase = mockk()
    private val ceremonyUseCase: SignatureCeremonyUseCase = mockk()
    private val documentRepository: DocumentRepositoryPort = mockk()
    private val service = OnboardingDocumentService(
        productCatalogPort,
        renderUseCase,
        documentQueryUseCase,
        ceremonyUseCase,
        documentRepository,
    )

    private val accountId: UUID = UUID.randomUUID()
    private val productId: UUID = UUID.randomUUID()
    private val partyRef = "party-1"
    private val idempotencyKey = "onboarding:$accountId"

    @Test
    fun `renders the product's bound template with no pinned version and opens a ceremony`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        val document = document()
        coEvery { renderUseCase.render(any()) } returns document
        coEvery { ceremonyUseCase.findByDocumentId(document.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns mockk()

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 1) {
            renderUseCase.render(
                RenderDocumentCommand(
                    templateCode = "RAMCOVA_SMLOUVA_CS",
                    templateVersion = null,
                    data = emptyMap(),
                    contentType = "application/pdf",
                    partyRef = partyRef,
                    caseRef = accountId.toString(),
                    productRef = productId.toString(),
                    retainUntil = null,
                    idempotencyKey = idempotencyKey,
                ),
            )
        }
        coVerify(exactly = 1) {
            ceremonyUseCase.openCeremony(
                OpenCeremonyCommand(
                    documentId = document.id,
                    signerPartyRefs = listOf(partyRef),
                    signatureLevel = SignatureLevel.ADVANCED,
                ),
            )
        }
    }

    @Test
    fun `skips render and ceremony when this account is already fully onboarded (idempotent replay)`(): Unit =
        runBlocking {
            val existing = document()
            coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns existing
            coEvery { ceremonyUseCase.findByDocumentId(existing.id) } returns mockk()

            service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

            coVerify(exactly = 0) { renderUseCase.render(any()) }
            coVerify(exactly = 0) { ceremonyUseCase.openCeremony(any()) }
        }

    @Test
    fun `resumes by opening the ceremony when the document exists but has no ceremony yet`(): Unit = runBlocking {
        // A crash landed the document but not its ceremony; the replay must NOT re-render, but MUST
        // open the missing ceremony.
        val existing = document()
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns existing
        coEvery { ceremonyUseCase.findByDocumentId(existing.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns mockk()

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 0) { renderUseCase.render(any()) }
        coVerify(exactly = 1) { ceremonyUseCase.openCeremony(any()) }
    }

    @Test
    fun `does nothing when the product has no documentTemplateCode bound`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns null

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 0) { renderUseCase.render(any()) }
        coVerify(exactly = 0) { ceremonyUseCase.openCeremony(any()) }
    }

    @Test
    fun `reuses the winning document when a concurrent delivery lost the render race`(): Unit = runBlocking {
        val winner = document()
        // First lookup misses; render loses the unique-key race; the retry lookup finds the winner.
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null andThen winner
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        coEvery { renderUseCase.render(any()) } throws DuplicateDocumentException("lost the race")
        coEvery { ceremonyUseCase.findByDocumentId(winner.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns mockk()

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 1) {
            ceremonyUseCase.openCeremony(match { it.documentId == winner.id })
        }
    }

    @Test
    fun `tolerates a concurrent ceremony open (idempotent, no error propagates)`(): Unit = runBlocking {
        val document = document()
        coEvery { documentQueryUseCase.findByIdempotencyKey(idempotencyKey) } returns null
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        coEvery { renderUseCase.render(any()) } returns document
        coEvery { ceremonyUseCase.findByDocumentId(document.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } throws DuplicateCeremonyException("lost the race")

        // Must not throw — the concurrent winner already opened the ceremony.
        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 1) { ceremonyUseCase.openCeremony(any()) }
    }

    // ── ensureOnboardingAgreement (ADR-0169 D3) ────────────────────────────────────────────────

    @Test
    fun `ensure renders a fresh agreement in the requested language and opens a ceremony`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns emptyList()
        val rendered = document(code = "RAMCOVA_SMLOUVA_EN")
        coEvery { renderUseCase.render(any()) } returns rendered
        coEvery { ceremonyUseCase.findByDocumentId(rendered.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(ceremonyId)

        val result = service.ensureOnboardingAgreement(partyRef, "en")

        assertThat(result.ceremonyId).isEqualTo(ceremonyId)
        assertThat(result.documentId).isEqualTo(rendered.id)
        assertThat(result.templateCode).isEqualTo("RAMCOVA_SMLOUVA_EN")
        coVerify(exactly = 1) { renderUseCase.render(match { it.templateCode == "RAMCOVA_SMLOUVA_EN" }) }
    }

    @Test
    fun `ensure reuses a pending agreement already in the requested language, without re-rendering`(): Unit =
        runBlocking {
            val ceremonyId = UUID.randomUUID()
            val existing = document(code = "RAMCOVA_SMLOUVA_CS", status = DocumentStatus.PENDING_SIGNATURE)
            coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(existing)
            coEvery { ceremonyUseCase.findByDocumentId(existing.id) } returns ceremony(ceremonyId)

            val result = service.ensureOnboardingAgreement(partyRef, "cs")

            assertThat(result.documentId).isEqualTo(existing.id)
            assertThat(result.ceremonyId).isEqualTo(ceremonyId)
            coVerify(exactly = 0) { renderUseCase.render(any()) }
        }

    @Test
    fun `ensure returns an already-signed agreement untouched, whatever language was asked`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        val signed = document(code = "RAMCOVA_SMLOUVA_CS", status = DocumentStatus.SIGNED)
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(signed)
        coEvery { ceremonyUseCase.findByDocumentId(signed.id) } returns ceremony(ceremonyId)

        val result = service.ensureOnboardingAgreement(partyRef, "en")

        assertThat(result.documentId).isEqualTo(signed.id)
        assertThat(result.documentStatus).isEqualTo(DocumentStatus.SIGNED)
        coVerify(exactly = 0) { renderUseCase.render(any()) }
    }

    @Test
    fun `ensure supersedes a pending agreement in a different language and re-renders`(): Unit = runBlocking {
        val ceremonyId = UUID.randomUUID()
        val stale = document(code = "RAMCOVA_SMLOUVA_CS", status = DocumentStatus.PENDING_SIGNATURE)
        val fresh = document(code = "RAMCOVA_SMLOUVA_EN")
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(stale)
        coEvery { documentRepository.save(any()) } answers { firstArg() }
        coEvery { renderUseCase.render(any()) } returns fresh
        coEvery { ceremonyUseCase.findByDocumentId(fresh.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(ceremonyId)

        val result = service.ensureOnboardingAgreement(partyRef, "en")

        assertThat(result.templateCode).isEqualTo("RAMCOVA_SMLOUVA_EN")
        // the stale CS document is archived before the EN render
        coVerify(exactly = 1) { documentRepository.save(match { it.status == DocumentStatus.ARCHIVED }) }
        coVerify(exactly = 1) { renderUseCase.render(match { it.templateCode == "RAMCOVA_SMLOUVA_EN" }) }
    }

    @Test
    fun `ensure falls back to the default locale for an unsupported language`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns emptyList()
        val rendered = document(code = "RAMCOVA_SMLOUVA_CS")
        coEvery { renderUseCase.render(any()) } returns rendered
        coEvery { ceremonyUseCase.findByDocumentId(rendered.id) } returns null
        coEvery { ceremonyUseCase.openCeremony(any()) } returns ceremony(UUID.randomUUID())

        service.ensureOnboardingAgreement(partyRef, "de")

        coVerify(exactly = 1) { renderUseCase.render(match { it.templateCode == "RAMCOVA_SMLOUVA_CS" }) }
    }

    private fun ceremony(id: UUID): SignatureCeremony = mockk { every { this@mockk.id } returns id }

    private fun document(code: String = "RAMCOVA_SMLOUVA_CS", status: DocumentStatus = DocumentStatus.GENERATED) =
        Document(
            id = UUID.randomUUID(),
            templateCode = code,
            templateVersion = "1.1.0",
            sha256 = "abc",
            storageKey = "documents/1",
            contentType = "application/pdf",
            sizeBytes = 10,
            status = status,
            metadata = emptyMap(),
            partyRef = partyRef,
            caseRef = accountId.toString(),
            productRef = productId.toString(),
            retainUntil = null,
            createdAt = Instant.parse("2026-01-15T10:15:30Z"),
            idempotencyKey = idempotencyKey,
        )
}
