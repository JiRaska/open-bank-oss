// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.application.usecase

import com.openbank.document.application.port.`in`.CreateTemplateCommand
import com.openbank.document.application.port.`in`.DocumentTemplateUseCase
import com.openbank.document.application.port.out.TemplateRenderPort
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.domain.model.TemplateEngine
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class DocumentTemplateService(
    private val repo: TemplateRepositoryPort,
    private val renderPort: TemplateRenderPort,
    private val clock: Clock,
) : DocumentTemplateUseCase {

    override suspend fun createTemplate(cmd: CreateTemplateCommand): DocumentTemplate {
        val template = DocumentTemplate(
            id = Ids.newId(),
            code = cmd.code,
            version = cmd.version,
            name = cmd.name,
            engine = cmd.engine,
            bodyHtml = cmd.bodyHtml,
            locale = cmd.locale,
            status = TemplateStatus.DRAFT,
            productRef = cmd.productRef,
            classification = cmd.classification,
            createdAt = Instant.now(clock),
            createdBy = cmd.createdBy,
        )
        return repo.save(template)
    }

    // TODO(ADR-0162): emit DocumentTemplatePublished to the outbox on publish once template-scoped
    // outbox co-persistence is wired (the DocumentGenerated path already demonstrates the pattern).
    override suspend fun publishTemplate(id: UUID): DocumentTemplate =
        repo.save((repo.findById(id) ?: error("Template not found: $id")).publish())

    override suspend fun retireTemplate(id: UUID): DocumentTemplate =
        repo.save((repo.findById(id) ?: error("Template not found: $id")).retire())

    override suspend fun getTemplate(id: UUID): DocumentTemplate? = repo.findById(id)

    override suspend fun listTemplates(limit: Int): List<DocumentTemplate> =
        repo.findAllTemplates(limit.coerceIn(1, MAX_LIMIT))

    override fun previewRender(bodyHtml: String, sampleData: Map<String, Any?>): String {
        // TemplateRenderPort.renderHtml takes a full DocumentTemplate, but a preview has no
        // persisted identity yet — this ephemeral instance exists only to carry [bodyHtml] through
        // the same real adapter (HandlebarsTemplateRenderer) the actual render path uses, so the
        // preview is never a fake/parallel implementation of the merge logic.
        val ephemeral = DocumentTemplate(
            id = PREVIEW_ID,
            code = "PREVIEW",
            version = "0.0.0",
            name = "preview",
            engine = TemplateEngine.HANDLEBARS,
            bodyHtml = bodyHtml,
            locale = "en",
            status = TemplateStatus.DRAFT,
            productRef = null,
            classification = "internal",
            createdAt = Instant.now(clock),
            createdBy = "preview",
        )
        return renderPort.renderHtml(ephemeral, sampleData)
    }

    private companion object {
        const val MAX_LIMIT = 200
        val PREVIEW_ID: UUID = UUID(0L, 0L)
    }
}
