// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.TemplateRepositoryPort
import com.openbank.document.domain.model.DocumentTemplate
import com.openbank.document.infrastructure.persistence.entity.DocumentTemplateEntity
import com.openbank.document.infrastructure.persistence.mapper.toDomain
import com.openbank.document.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DocumentTemplateRepositoryImpl :
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

    private fun DocumentTemplateEntity.applyFrom(template: DocumentTemplate) {
        status = template.status
        name = template.name
        bodyHtml = template.bodyHtml
        productRef = template.productRef
        classification = template.classification
    }
}
