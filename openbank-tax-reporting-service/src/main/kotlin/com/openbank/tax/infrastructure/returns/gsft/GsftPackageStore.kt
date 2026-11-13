// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.infrastructure.returns.gsft

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A stored GSFT archive: its name and the exact ZIP bytes whose SHA-256 a return attests. */
class StoredGsftPackage(val archiveName: String, val archive: ByteArray)

/** The encrypted artefact handed over for one return. */
class StoredGsftDelivery(val fileName: String, val encrypted: ByteArray)

/**
 * The GSFT tables of V7, written with single native statements so concurrent writers never race:
 * an archive or a delivery is `INSERT … ON CONFLICT DO NOTHING` then re-read (the first writer wins
 * and every caller sees the same row), and the batch number is one atomic upsert.
 */
@ApplicationScoped
class GsftPackageStore {
    /** Idempotent: the same archive is the same row, so an attested archive is never rewritten. */
    suspend fun put(archiveName: String, archive: ByteArray, at: Instant): String {
        val sha = sha256Hex(archive)
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO gsft_package (archive_sha256, archive_name, archive, created_at) " +
                        "VALUES (:sha, :name, :archive, :at) ON CONFLICT (archive_sha256) DO NOTHING",
                ).setParameter("sha", sha).setParameter("name", archiveName).setParameter("archive", archive)
                    .setParameter("at", utc(at)).executeUpdate()
            }
        }.awaitSuspending()
        return sha
    }

    suspend fun get(archiveSha256: String): StoredGsftPackage? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any?>>(
                "SELECT archive_name, archive FROM gsft_package WHERE archive_sha256 = :sha",
            ).setParameter("sha", archiveSha256).resultList
        }
    }.awaitSuspending().firstOrNull()?.let { StoredGsftPackage(it[0] as String, it[1] as ByteArray) }

    /**
     * Records the artefact for [returnId] unless one is already recorded, and returns the one that
     * is: a second concurrent delivery hands over exactly what the first one stored.
     */
    suspend fun putDelivery(returnId: UUID, fileName: String, encrypted: ByteArray, at: Instant): StoredGsftDelivery {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeMutationQuery(
                    "INSERT INTO gsft_delivery (return_id, file_name, encrypted, created_at) " +
                        "VALUES (:id, :name, :bytes, :at) ON CONFLICT (return_id) DO NOTHING",
                ).setParameter("id", returnId).setParameter("name", fileName).setParameter("bytes", encrypted)
                    .setParameter("at", utc(at)).executeUpdate()
            }
        }.awaitSuspending()
        return checkNotNull(delivery(returnId)) { "ON CONFLICT missed for return $returnId yet no delivery is stored" }
    }

    suspend fun delivery(returnId: UUID): StoredGsftDelivery? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Array<Any?>>(
                "SELECT file_name, encrypted FROM gsft_delivery WHERE return_id = :id",
            ).setParameter("id", returnId).resultList
        }
    }.awaitSuspending().firstOrNull()?.let { StoredGsftDelivery(it[0] as String, it[1] as ByteArray) }

    /** The next batch number for [bankCode]: one atomic upsert, shared by TEST and LIVE. */
    suspend fun nextBatchNumber(bankCode: String): Int = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Int>(
                "INSERT INTO gsft_batch_counter (bank_code, last_number) VALUES (:bank, 1) " +
                    "ON CONFLICT (bank_code) DO UPDATE SET last_number = gsft_batch_counter.last_number + 1 " +
                    "RETURNING last_number",
                Int::class.javaObjectType,
            ).setParameter("bank", bankCode).singleResult
        }
    }.awaitSuspending()

    private fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
