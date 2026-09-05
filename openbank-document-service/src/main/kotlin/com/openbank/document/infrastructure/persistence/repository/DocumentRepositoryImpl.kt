// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.document.application.port.out.DocumentRepositoryPort
import com.openbank.document.application.port.out.DuplicateDocumentException
import com.openbank.document.domain.model.Document
import com.openbank.document.infrastructure.persistence.PostgresConflicts
import com.openbank.document.infrastructure.persistence.entity.DocumentEntity
import com.openbank.document.infrastructure.persistence.mapper.toDomain
import com.openbank.document.infrastructure.persistence.mapper.toEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class DocumentRepositoryImpl :
    DocumentRepositoryPort,
    PanacheRepository<DocumentEntity> {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var outboxRepo: DocumentOutboxRepositoryImpl

    /**
     * Upsert, not insert: [save]'s callers hand it a document that already exists — superseding an
     * agreement archives it in place ([Document.archive] keeps the id, only the status changes).
     * A plain `persist()` of an id the table already holds is an INSERT, which the primary key
     * rejects: `duplicate key value violates unique constraint "documents_pkey"` surfacing as a
     * bare 500 (seen live 2026-07-16 on every onboarding language switch, ADR-0169 D3).
     *
     * Mirrors [saveWithOutbox]'s find-then-apply shape, minus the outbox leg. Both run inside
     * `withTransaction`, so mutating the managed entity is flushed on commit without an explicit
     * update call.
     */
    override suspend fun save(document: Document): Document = Panache.withTransaction {
        find("id", document.id).firstResult().flatMap { existing ->
            if (existing != null) {
                existing.applyFrom(document)
                Uni.createFrom().item(document)
            } else {
                persist(document.toEntity(objectMapper)).replaceWith(document)
            }
        }
    }.awaitSuspending()

    override suspend fun findById(id: UUID): Document? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain(objectMapper)

    override suspend fun findByParty(partyRef: String): List<Document> =
        Panache.withSession { find("partyRef", partyRef).list() }
            .awaitSuspending().map { it.toDomain(objectMapper) }

    // Ordered explicitly: an unordered page is not a page. Without a deterministic sort Postgres
    // may return rows in any order per query, so the same offset can repeat or skip a document
    // between two requests — paging over it silently loses rows. createdAt DESC also matches the
    // browse intent (newest first) and id breaks ties so the order is total, not merely mostly-total.
    override suspend fun findByPartyPaged(partyRef: String, page: Int, size: Int): List<Document> =
        Panache.withSession {
            find("partyRef = ?1 order by createdAt desc, id", partyRef).page(page, size).list()
        }.awaitSuspending().map { it.toDomain(objectMapper) }

    override suspend fun countByParty(partyRef: String): Long =
        Panache.withSession { count("partyRef", partyRef) }.awaitSuspending()

    override suspend fun findByIdempotencyKey(idempotencyKey: String): Document? =
        Panache.withSession { find("idempotencyKey", idempotencyKey).firstResult() }
            .awaitSuspending()?.toDomain(objectMapper)

    override suspend fun saveWithOutbox(document: Document, outboxMessage: OutboxMessage): Document =
        Panache.withTransaction {
            find("id", document.id).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.applyFrom(document)
                    outboxRepo.persistInTransaction(outboxMessage).replaceWith(document)
                } else {
                    persist(document.toEntity(objectMapper))
                        .chain { _ -> outboxRepo.persistInTransaction(outboxMessage) }
                        .replaceWith(document)
                }
            }
        }.onFailure().transform { e ->
            // Translate the idempotency-key unique-violation at the persistence boundary so the
            // application layer catches a typed DuplicateDocumentException, never a raw SQL/Hibernate
            // exception (ADR-0002). Any other failure passes through unchanged.
            if (PostgresConflicts.isUniqueViolation(e)) {
                DuplicateDocumentException("A document already exists for idempotency key ${document.idempotencyKey}")
            } else {
                e
            }
        }.awaitSuspending()

    private fun DocumentEntity.applyFrom(document: Document) {
        status = document.status
        storageKey = document.storageKey
        sha256 = document.sha256
        sizeBytes = document.sizeBytes
        contentType = document.contentType
        // MUST propagate the idempotency key: Document.archive() nulls it so the partial unique
        // index (uq_documents_idempotency_key, WHERE idempotency_key IS NOT NULL) is released and a
        // fresh agreement can re-render under the same key. Omitting it left the archived row
        // holding the key, so ensureOnboardingAgreement's re-render hit a DuplicateDocumentException
        // and its fallback resolved back to the ARCHIVED document — whose ceremony then failed to
        // sign with "Only PENDING_SIGNATURE documents can be signed" (every onboarding language switch).
        idempotencyKey = document.idempotencyKey
    }
}
