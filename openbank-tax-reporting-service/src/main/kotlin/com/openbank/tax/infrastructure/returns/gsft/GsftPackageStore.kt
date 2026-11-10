// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.security.MessageDigest
import java.time.Instant

@Entity
@Table(name = "gsft_package")
class GsftPackageEntity : PanacheEntityBase {
    @Id
    @Column(name = "archive_sha256")
    var archiveSha256: String = ""

    @Column(name = "archive_name", nullable = false)
    var archiveName: String = ""

    @Column(name = "archive", nullable = false)
    var archive: ByteArray = ByteArray(0)

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH
}

/** A stored GSFT archive: its name and the exact ZIP bytes whose SHA-256 a return attests. */
class StoredGsftPackage(val archiveName: String, val archive: ByteArray)

/**
 * Content-addressed store of assembled GSFT archives (V7). [put] is idempotent: the same archive
 * is the same row, so re-assembling an identical batch never rewrites what was attested.
 */
@ApplicationScoped
class GsftPackageStore : PanacheRepositoryBase<GsftPackageEntity, String> {
    suspend fun put(archiveName: String, archive: ByteArray, at: Instant): String {
        val sha = sha256Hex(archive)
        Panache.withTransaction {
            findById(sha).flatMap { existing ->
                if (existing != null) {
                    io.smallrye.mutiny.Uni.createFrom().item(existing)
                } else {
                    persist(
                        GsftPackageEntity().apply {
                            archiveSha256 = sha
                            this.archiveName = archiveName
                            this.archive = archive
                            createdAt = at
                        },
                    )
                }
            }
        }.awaitSuspending()
        return sha
    }

    suspend fun get(archiveSha256: String): StoredGsftPackage? =
        Panache.withSession { findById(archiveSha256) }.awaitSuspending()
            ?.let { StoredGsftPackage(it.archiveName, it.archive) }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
