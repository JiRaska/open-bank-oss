// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.`in`.DocumentRenderUseCase
import com.openbank.document.application.port.`in`.RenderDocumentCommand
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.PdfRenderPort
import com.openbank.document.application.port.out.TemplateRenderPort
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.event.DocumentGenerated
import com.openbank.document.domain.model.Document
import com.openbank.document.domain.model.DocumentStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.storage.ObjectStorePort
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

/**
 * Renders a document from a published template + data map: merge → HTML → PDF → object store →
 * content-addressed [Document] persisted together with a [DocumentGenerated] outbox event in one
 * transaction (transactional-outbox pattern, ADR-0050).
 */
@ApplicationScoped
class DocumentRenderService(
    private val templateRepo: TemplateRepositoryPort,
    private val documentRepo: DocumentRepositoryPort,
    private val templateRenderPort: TemplateRenderPort,
    private val pdfRenderPort: PdfRenderPort,
    private val objectStore: ObjectStorePort,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : DocumentRenderUseCase {

    override suspend fun render(cmd: RenderDocumentCommand): Document {
        // ADR-0162 version-resolution policy: an explicit templateVersion pins to that exact
        // (immutable) version; omitting it resolves to whatever is currently PUBLISHED for
        // templateCode. Either way, the resolved template.code/version is what gets snapshotted
        // onto the resulting Document below, so a later re-publish can never retroactively change
        // what this already-generated document is considered to have been rendered from.
        val pinnedVersion = cmd.templateVersion
        val template = if (pinnedVersion != null) {
            templateRepo.findPublished(cmd.templateCode, pinnedVersion)
                ?: error("No published template for ${cmd.templateCode} v$pinnedVersion")
        } else {
            templateRepo.findLatestPublished(cmd.templateCode)
                ?: error("No published template for ${cmd.templateCode}")
        }

        val html = templateRenderPort.renderHtml(template, cmd.data)
        val pdf = pdfRenderPort.htmlToPdf(html)

        val id = Ids.newId()
        val storageKey = "documents/$id"
        objectStore.put(storageKey, pdf, cmd.contentType)

        val now = Instant.now(clock)
        val document = Document(
            id = id,
            templateCode = template.code,
            templateVersion = template.version,
            sha256 = Document.sha256(pdf),
            storageKey = storageKey,
            contentType = cmd.contentType,
            sizeBytes = pdf.size.toLong(),
            status = DocumentStatus.GENERATED,
            metadata = mapOf(
                "templateCode" to template.code,
                "templateVersion" to template.version,
                "locale" to template.locale,
            ),
            partyRef = cmd.partyRef,
            caseRef = cmd.caseRef,
            productRef = cmd.productRef,
            retainUntil = cmd.retainUntil,
            createdAt = now,
            idempotencyKey = cmd.idempotencyKey,
        )

        val outboxMessage = OutboxMessage(
            eventId = Ids.newId(),
            aggregateId = id,
            eventType = EVENT_DOCUMENT_GENERATED,
            payload = objectMapper.writeValueAsString(
                DocumentGenerated(
                    documentId = id,
                    templateCode = template.code,
                    templateVersion = template.version,
                    sha256 = document.sha256,
                    occurredAt = now,
                ),
            ),
        )
        return documentRepo.saveWithOutbox(document, outboxMessage)
    }

    companion object {
        const val EVENT_DOCUMENT_GENERATED = "document.generated.v1"
    }
}
