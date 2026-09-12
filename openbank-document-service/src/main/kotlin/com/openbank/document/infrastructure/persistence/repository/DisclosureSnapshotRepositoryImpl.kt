// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.persistence.repository

import com.openbank.document.application.port.out.DisclosureSnapshotRepository
import com.openbank.document.domain.model.DisclosureSnapshot
import com.openbank.document.infrastructure.persistence.entity.DisclosureSnapshotEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class DisclosureSnapshotRepositoryImpl :
    DisclosureSnapshotRepository,
    PanacheRepository<DisclosureSnapshotEntity> {

    override suspend fun createOrFind(snapshot: DisclosureSnapshot): DisclosureSnapshot = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(INSERT_SQL)
                .setParameter("id", snapshot.id)
                .setParameter("requestId", snapshot.requestId)
                .setParameter("sourceDocumentId", snapshot.sourceDocumentId)
                .setParameter("partyRef", snapshot.partyRef)
                .setParameter("sourceSha256", snapshot.sourceSha256)
                .setParameter("sha256", snapshot.sha256)
                .setParameter("storageKey", snapshot.storageKey)
                .setParameter("contentType", snapshot.contentType)
                .setParameter("sizeBytes", snapshot.sizeBytes)
                .setParameter("createdAt", snapshot.createdAt)
                .executeUpdate()
                .flatMap { find("requestId", snapshot.requestId).firstResult<DisclosureSnapshotEntity>() }
                .map { requireNotNull(it) { "disclosure snapshot insert/replay produced no row" }.toDomain() }
        }
    }.awaitSuspending()

    override suspend fun findById(id: UUID): DisclosureSnapshot? = Panache.withSession {
        find("id", id).firstResult<DisclosureSnapshotEntity>()
    }.awaitSuspending()?.toDomain()

    override suspend fun findByRequestId(requestId: UUID): DisclosureSnapshot? = Panache.withSession {
        find("requestId", requestId).firstResult<DisclosureSnapshotEntity>()
    }.awaitSuspending()?.toDomain()

    private companion object {
        val INSERT_SQL = """
            INSERT INTO disclosure_snapshots
                (id, request_id, source_document_id, party_ref, source_sha256, sha256,
                 storage_key, content_type, size_bytes, created_at)
            VALUES
                (:id, :requestId, :sourceDocumentId, :partyRef, :sourceSha256, :sha256,
                 :storageKey, :contentType, :sizeBytes, :createdAt)
            ON CONFLICT (request_id) DO NOTHING
        """.trimIndent()
    }
}
