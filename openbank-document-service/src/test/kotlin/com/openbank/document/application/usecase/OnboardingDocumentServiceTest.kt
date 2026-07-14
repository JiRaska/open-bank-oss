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
import com.openbank.document.application.port.out.ProductCatalogPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.SignatureLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * ADR-0162 D7: the onboarding wiring exercises product-catalog's `documentTemplateCode` reference
 * and the render use-case's version-resolution policy (`templateVersion = null`) end to end for
 * the first time — this test pins that contract, plus the idempotency guarantee a Kafka consumer
 * calling this under at-least-once delivery relies on.
 */
class OnboardingDocumentServiceTest {

    private val productCatalogPort: ProductCatalogPort = mockk()
    private val renderUseCase: DocumentRenderUseCase = mockk()
    private val documentQueryUseCase: DocumentQueryUseCase = mockk()
    private val ceremonyUseCase: SignatureCeremonyUseCase = mockk()
    private val service =
        OnboardingDocumentService(productCatalogPort, renderUseCase, documentQueryUseCase, ceremonyUseCase)

    private val accountId: UUID = UUID.randomUUID()
    private val productId: UUID = UUID.randomUUID()
    private val partyRef = "party-1"

    @Test
    fun `renders the product's bound template with no pinned version and opens a ceremony`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns emptyList()
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns "RAMCOVA_SMLOUVA_CS"
        val document = document()
        coEvery { renderUseCase.render(any()) } returns document
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
    fun `skips when this account already has an issued document (idempotent replay)`(): Unit = runBlocking {
        val existing = document().copy(caseRef = accountId.toString())
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns listOf(existing)

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 0) { renderUseCase.render(any()) }
        coVerify(exactly = 0) { ceremonyUseCase.openCeremony(any()) }
    }

    @Test
    fun `does nothing when the product has no documentTemplateCode bound`(): Unit = runBlocking {
        coEvery { documentQueryUseCase.listByParty(partyRef) } returns emptyList()
        coEvery { productCatalogPort.findDocumentTemplateCode(productId) } returns null

        service.issueOnboardingDocument(IssueOnboardingDocumentCommand(accountId, partyRef, productId))

        coVerify(exactly = 0) { renderUseCase.render(any()) }
        coVerify(exactly = 0) { ceremonyUseCase.openCeremony(any()) }
    }

    private fun document() = Document(
        id = UUID.randomUUID(),
        templateCode = "RAMCOVA_SMLOUVA_CS",
        templateVersion = "1.1.0",
        sha256 = "abc",
        storageKey = "documents/1",
        contentType = "application/pdf",
        sizeBytes = 10,
        status = DocumentStatus.GENERATED,
        metadata = emptyMap(),
        partyRef = partyRef,
        caseRef = null,
        productRef = productId.toString(),
        retainUntil = null,
        createdAt = Instant.parse("2026-01-15T10:15:30Z"),
    )
}
