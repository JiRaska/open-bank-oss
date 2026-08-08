// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
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
import com.openbank.libs.testing.audit.AuditEventTime
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

    // A hand-built mapper must mirror the CDI-injected one or the test asserts against a wire format
    // production never emits: Quarkus disables WRITE_DATES_AS_TIMESTAMPS, a bare JavaTimeModule does
    // not, and the difference is an ISO-8601 string vs an epoch-seconds NUMBER. `AuditConsumer` parses
    // the event time with `Instant.parse`, so the numeric form is unreadable to it — the audit-event-
    // time test below is red against a timestamps-on mapper even with the field correctly named (#3914).
    // Same reason DelegationEventPactFolderProviderVerificationTest states in place.
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
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

    /**
     * #3914: the audit trail must record WHEN the document was generated, not when the audit
     * consumer got round to the event. Red before the `at` -> `occurredAt` rename: the payload
     * carried the instant under a name `AuditConsumer` does not read, so every row for
     * `openbank.documents.document.event` was sourced INGEST.
     */
    @Test
    fun `the DocumentGenerated payload carries the generation instant as the audit event time`(): Unit = runBlocking {
        val pdf = "<html>rendered</html>".toByteArray()
        coEvery { templateRepo.findPublished("LOAN_AGREEMENT", "1.0.0") } returns publishedTemplate()
        coEvery { renderPort.renderHtml(any(), any()) } returns "<html>rendered</html>"
        coEvery { pdfPort.htmlToPdf(any()) } returns pdf
        coEvery { objectStore.put(any(), any(), any()) } returns Unit
        val savedMsg = slot<OutboxMessage>()
        coEvery { documentRepo.saveWithOutbox(any(), capture(savedMsg)) } answers { firstArg() }

        service.render(command())

        AuditEventTime.assertRecordedAsEventTime(savedMsg.captured.payload, FIXED_NOW)
    }

    @Test
    fun `render fails when no published template matches`(): Unit = runBlocking {
        coEvery { templateRepo.findPublished(any(), any()) } returns null

        assertThatThrownBy { runBlocking { service.render(command()) } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `render with no templateVersion resolves the current published version`(): Unit = runBlocking {
        val pdf = "<html>rendered</html>".toByteArray()
        coEvery { templateRepo.findLatestPublished("LOAN_AGREEMENT") } returns publishedTemplate()
        coEvery { renderPort.renderHtml(any(), any()) } returns "<html>rendered</html>"
        coEvery { pdfPort.htmlToPdf(any()) } returns pdf
        coEvery { objectStore.put(any(), any(), any()) } returns Unit
        val savedDoc = slot<Document>()
        coEvery { documentRepo.saveWithOutbox(capture(savedDoc), any()) } answers { savedDoc.captured }

        val result = service.render(command(templateVersion = null))

        assertThat(result.templateVersion).isEqualTo("1.0.0")
        coVerify(exactly = 1) { templateRepo.findLatestPublished("LOAN_AGREEMENT") }
        coVerify(exactly = 0) { templateRepo.findPublished(any(), any()) }
    }

    @Test
    fun `render with no templateVersion fails when nothing is currently published`(): Unit = runBlocking {
        coEvery { templateRepo.findLatestPublished(any()) } returns null

        assertThatThrownBy { runBlocking { service.render(command(templateVersion = null)) } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun command(templateVersion: String? = "1.0.0") = RenderDocumentCommand(
        templateCode = "LOAN_AGREEMENT",
        templateVersion = templateVersion,
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
