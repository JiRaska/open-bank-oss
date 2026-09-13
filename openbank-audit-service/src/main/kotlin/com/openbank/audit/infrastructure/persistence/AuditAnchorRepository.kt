// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.infrastructure.persistence

import com.openbank.audit.domain.model.AuditAnchor
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "audit_anchor")
class AuditAnchorEntity : PanacheEntity() {
    @Column(name = "last_entry_id")
    var lastEntryId: UUID? = null

    @Column(name = "last_record_hash")
    var lastRecordHash: String? = null

    @Column(name = "chained_count", nullable = false)
    var chainedCount: Long = 0

    @Column(name = "chain_status", nullable = false)
    lateinit var chainStatus: String

    @Column(name = "anchor_digest", nullable = false)
    lateinit var anchorDigest: String

    @Column(name = "signature")
    var signature: String? = null

    @Column(name = "key_id", nullable = false)
    lateinit var keyId: String

    @Column(name = "signed_at", nullable = false)
    lateinit var signedAt: Instant
}

@ApplicationScoped
class AuditAnchorRepository : PanacheRepository<AuditAnchorEntity> {

    suspend fun save(anchor: AuditAnchor) {
        val e = AuditAnchorEntity().also {
            it.lastEntryId = anchor.lastEntryId
            it.lastRecordHash = anchor.lastRecordHash
            it.chainedCount = anchor.chainedCount
            it.chainStatus = anchor.chainStatus
            it.anchorDigest = anchor.anchorDigest
            it.signature = anchor.signature
            it.keyId = anchor.keyId
            it.signedAt = anchor.signedAt
        }
        Panache.withTransaction { persist(e) }.awaitSuspending()
    }

    suspend fun recent(limit: Int): List<AuditAnchor> = Panache.withSession {
        find("ORDER BY id DESC").page(0, limit).list()
    }.awaitSuspending().map { it.toDomain() }

    suspend fun all(): List<AuditAnchor> = Panache.withSession {
        find("ORDER BY id ASC").list()
    }.awaitSuspending().map { it.toDomain() }

    /** Limits public-key lookup to immutable key identifiers actually recorded on this audit log. */
    suspend fun hasKeyId(keyId: String): Boolean = Panache.withSession {
        count("keyId", keyId)
    }.awaitSuspending() > 0

    private fun AuditAnchorEntity.toDomain() = AuditAnchor(
        lastEntryId = lastEntryId,
        lastRecordHash = lastRecordHash,
        chainedCount = chainedCount,
        chainStatus = chainStatus,
        anchorDigest = anchorDigest,
        signature = signature,
        keyId = keyId,
        signedAt = signedAt,
    )
}
