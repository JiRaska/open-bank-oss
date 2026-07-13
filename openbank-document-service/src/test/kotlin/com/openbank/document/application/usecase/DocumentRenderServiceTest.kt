// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.PdfRenderPort
import com.openbank.document.application.port.out.TemplateRenderPort
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.storage.ObjectStorePort
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
import java.time.ZoneOffset

class DocumentRenderServiceTest {

    private val templateRepo: TemplateRepositoryPort = mockk()
    private val documentRepo: DocumentRepositoryPort = mockk()
    private val renderPort: TemplateRenderPort = mockk()
    private val pdfPort: PdfRenderPort = mockk()
    private val objectStore: ObjectStorePort = mockk()
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val service = DocumentRenderService(
        templateRepo = templateRepo,
        documentRepo = documentRepo,
        templateRenderPort = renderPort,
        pdfRenderPort = pdfPort,
        objectStore = objectStore,
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
        objectMapper = mapper,
    )

    @Test
    fun `render merges the template, stores the bytes and emits a DocumentGenerated event`(): Unit = runBlocking {
        val pdf = "<html>rendered</html>".toByteArray()
        coEvery { templateRepo.findPublished("LOAN_AGREEMENT", "1.0.0") } returns publishedTemplate()
        coEvery { renderPort.renderHtml(any(), any()) } returns "<html>rendered</html>"
        coEvery { pdfPort.htmlToPdf(any()) } returns pdf
        coEvery { objectStore.put(any(), any(), any()) } returns Unit
        val savedDoc = slot<Document>()
        val savedMsg = slot<OutboxMessage>()
        coEvery { documentRepo.saveWithOutbox(capture(savedDoc), capture(savedMsg)) } answers { savedDoc.captured }

        val result = service.render(command())

        assertThat(result.status).isEqualTo(DocumentStatus.GENERATED)
        assertThat(result.sha256).isEqualTo(Document.sha256(pdf))
        assertThat(result.sizeBytes).isEqualTo(pdf.size.toLong())
        assertThat(savedMsg.captured.eventType).isEqualTo(DocumentRenderService.EVENT_DOCUMENT_GENERATED)
        coVerify(exactly = 1) { renderPort.renderHtml(any(), any()) }
        coVerify(exactly = 1) { objectStore.put(any(), pdf, "application/pdf") }
        coVerify(exactly = 1) { documentRepo.saveWithOutbox(any(), any()) }
    }

    @Test
    fun `render fails when no published template matches`(): Unit = runBlocking {
        coEvery { templateRepo.findPublished(any(), any()) } returns null

        assertThatThrownBy { runBlocking { service.render(command()) } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun command() = RenderDocumentCommand(
        templateCode = "LOAN_AGREEMENT",
        templateVersion = "1.0.0",
        data = mapOf("name" to "Alice"),
        contentType = "application/pdf",
        partyRef = "party-1",
        caseRef = null,
        productRef = null,
        retainUntil = null,
    )

    private fun publishedTemplate() = DocumentTemplate(
        id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000020"),
        code = "LOAN_AGREEMENT",
        version = "1.0.0",
        name = "Loan agreement",
        engine = TemplateEngine.HANDLEBARS,
        bodyHtml = "<html>{{name}}</html>",
        locale = "en",
        status = TemplateStatus.PUBLISHED,
        productRef = null,
        classification = "restricted",
        createdAt = FIXED_NOW,
        createdBy = "system",
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-01-15T10:15:30Z")
    }
}
