// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.TemplatePublishConflictException
import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.infrastructure.persistence.PostgresConflicts
import com.openbank.document.infrastructure.persistence.entity.DocumentTemplateEntity
import com.openbank.document.infrastructure.persistence.mapper.toDomain
import com.openbank.document.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class DocumentTemplateRepositoryImpl(private val sf: Mutiny.SessionFactory) :
    TemplateRepositoryPort,
    PanacheRepository<DocumentTemplateEntity> {

    override suspend fun save(template: DocumentTemplate): DocumentTemplate {
        Panache.withTransaction {
            find("id", template.id).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.applyFrom(template)
                    Uni.createFrom().item(existing)
                } else {
                    persist(template.toEntity())
                }
            }
        }.awaitSuspending()
        return template
    }

    override suspend fun findById(id: UUID): DocumentTemplate? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findPublished(code: String, version: String): DocumentTemplate? = Panache.withSession {
        find("code = ?1 and version = ?2 and status = 'PUBLISHED'", code, version).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findAllTemplates(limit: Int): List<DocumentTemplate> =
        Panache.withSession { findAll().page(Page.ofSize(limit)).list() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun findLatestPublished(code: String): DocumentTemplate? = Panache.withSession {
        find(
            "code = ?1 and status = 'PUBLISHED'",
            Sort.by("createdAt", Sort.Direction.Descending).and("id", Sort.Direction.Descending),
            code,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    // Retire-then-publish, with an explicit flush in between: Postgres does not support DEFERRABLE
    // on a *partial* unique index (only non-partial constraint-backed uniques can defer), so
    // `uq_document_templates_one_published_per_code` is checked immediately, per-statement. Simply
    // mutating both entities and letting Hibernate flush them in whatever order it likes risks the
    // publish's UPDATE reaching Postgres before the retire's -- transiently violating the index
    // even inside one transaction that would have been fine at commit. Flushing the retire first
    // guarantees the old row is already RETIRED in the database by the time the new row's UPDATE
    // (which the outer withTransaction's own commit-time flush emits) runs.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun publishReplacing(toPublish: DocumentTemplate, toRetire: DocumentTemplate?): DocumentTemplate {
        try {
            sf.withTransaction { s ->
                val retired = if (toRetire == null) {
                    Uni.createFrom().voidItem()
                } else {
                    s.find(DocumentTemplateEntity::class.java, toRetire.id).flatMap { foundRetire ->
                        val retiring = checkNotNull(foundRetire) { "Template not found: ${toRetire.id}" }
                        retiring.applyFrom(toRetire)
                        s.flush()
                    }
                }
                retired.flatMap {
                    s.find(DocumentTemplateEntity::class.java, toPublish.id).map { found ->
                        val publishing = checkNotNull(found) { "Template not found: ${toPublish.id}" }
                        publishing.applyFrom(toPublish)
                        publishing
                    }
                }
            }.awaitSuspending()
        } catch (e: RuntimeException) {
            if (PostgresConflicts.isUniqueViolation(e)) {
                throw TemplatePublishConflictException(
                    "Template ${toPublish.code} already has a PUBLISHED version (concurrent publish lost the race)",
                )
            }
            throw e
        }
        return toPublish
    }

    private fun DocumentTemplateEntity.applyFrom(template: DocumentTemplate) {
        status = template.status
        name = template.name
        bodyHtml = template.bodyHtml
        productRef = template.productRef
        classification = template.classification
    }
}
