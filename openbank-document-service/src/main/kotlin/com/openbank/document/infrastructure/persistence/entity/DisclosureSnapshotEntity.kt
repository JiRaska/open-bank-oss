// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.document.infrastructure.persistence.entity

import com.openbank.document.domain.model.DisclosureSnapshot
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "disclosure_snapshots")
class DisclosureSnapshotEntity : PanacheEntityBase() {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(name = "request_id", nullable = false, updatable = false, unique = true)
    lateinit var requestId: UUID

    @Column(name = "source_document_id", nullable = false, updatable = false)
    lateinit var sourceDocumentId: UUID

    @Column(name = "party_ref", nullable = false, updatable = false, length = 200)
    lateinit var partyRef: String

    @Column(name = "source_sha256", nullable = false, updatable = false, length = 64)
    lateinit var sourceSha256: String

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    lateinit var sha256: String

    @Column(name = "storage_key", nullable = false, updatable = false, length = 300)
    lateinit var storageKey: String

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    lateinit var contentType: String

    @Column(name = "size_bytes", nullable = false, updatable = false)
    var sizeBytes: Long = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    fun toDomain() = DisclosureSnapshot(
        id,
        requestId,
        sourceDocumentId,
        partyRef,
        sourceSha256,
        sha256,
        storageKey,
        contentType,
        sizeBytes,
        createdAt,
    )
}
