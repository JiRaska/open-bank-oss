// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.storage

import io.quarkus.arc.properties.IfBuildProperty
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Clock
import java.time.Instant

/**
 * Backing entity for [PostgresBlobStore] (ADR-0161 D2).
 *
 * Unlike `PanacheOutboxEntity` (a `@MappedSuperclass` that every service
 * subclasses under its own `@Table` name, because outbox tables are
 * service-specific), `object_store_blobs` is a generic, technical table with
 * the SAME shape and name in every consuming service — there is no per-service
 * domain data to distinguish it, so this class is declared concrete directly
 * here rather than as an abstract base a service must subclass.
 *
 * Each consuming service opting into `openbank.objectstore.backend=postgres`
 * has its OWN isolated Postgres database (ADR-0009 — no shared cross-service
 * schema), so the identical table name in multiple services' databases is not
 * a collision. Any such service MUST add its OWN Flyway migration creating this
 * table:
 *
 * ```sql
 * CREATE TABLE object_store_blobs (
 *     storage_key   VARCHAR(1024) PRIMARY KEY,
 *     content       BYTEA         NOT NULL,
 *     content_type  VARCHAR(255)  NOT NULL,
 *     metadata      JSONB,
 *     created_at    TIMESTAMPTZ   NOT NULL
 * );
 * ```
 *
 * Adding `openbank-libs-runtime` as a dependency makes this class visible to
 * Hibernate Reactive's Jandex-based entity scan regardless of whether the
 * service actually uses the Postgres backend — every query against it simply
 * fails until the service's own migration has created the table, matching the
 * existing failure mode of every other optional-backend adapter in this module.
 */
@Entity
@Table(name = "object_store_blobs")
class ObjectStoreBlobEntity : PanacheEntityBase {

    @Id
    @Column(name = "storage_key", nullable = false, length = 1024)
    lateinit var storageKey: String

    @Column(name = "content", nullable = false)
    lateinit var content: ByteArray

    @Column(name = "content_type", nullable = false)
    lateinit var contentType: String

    @Column(name = "metadata")
    var metadataJson: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant
}

@ApplicationScoped
class ObjectStoreBlobRepository : PanacheRepositoryBase<ObjectStoreBlobEntity, String>

/**
 * Dev/test/low-volume [ObjectStorePort] adapter (ADR-0161 D2) — bytes live in a
 * per-service `BYTEA` table ([ObjectStoreBlobEntity]), the same shape
 * `openbank-party-service`'s `V10__party_document_files.sql` already uses. This
 * is the honest replacement for that migration's TODO: the *port* is the
 * contract, this table is one valid backing of it, not a placeholder for a
 * "real" implementation still to come.
 *
 * Selected by `openbank.objectstore.backend=postgres`, which is also the
 * **default when the key is absent** (`enableIfMissing = true`) so
 * `@QuarkusTest` and local dev need no S3/localstack — see [S3ObjectStore] for
 * the production adapter.
 *
 * [presignGet]: Postgres `BYTEA` has no native pre-signing mechanism — there is
 * no separate object-storage endpoint to hand out a scoped, time-boxed URL for.
 * Returning a URL that *looks* like a real pre-signed link but silently isn't
 * one is the more dangerous failure mode (a caller could cache/forward it
 * expecting S3 semantics), so this adapter throws [UnsupportedOperationException]
 * instead — honest and loud rather than quietly wrong. Callers on this backend
 * must fetch bytes via [get] through their own authenticated/authorized REST
 * endpoint.
 */
@ApplicationScoped
@IfBuildProperty(name = "openbank.objectstore.backend", stringValue = "postgres", enableIfMissing = true)
class PostgresBlobStore(private val repository: ObjectStoreBlobRepository, private val clock: Clock) :
    ObjectStorePort {

    override suspend fun put(key: String, bytes: ByteArray, contentType: String, metadata: Map<String, String>) {
        val entity = ObjectStoreBlobEntity().apply {
            storageKey = key
            content = bytes
            this.contentType = contentType
            metadataJson = metadataToJson(metadata)
            createdAt = Instant.now(clock)
        }
        // put() overwrites: delete-then-insert in one transaction rather than persist()
        // directly, since persist() on a detached instance with an already-used @Id would
        // hit the primary-key constraint instead of replacing the row.
        Panache.withTransaction {
            repository.deleteById(key).chain { _ -> repository.persist(entity) }
        }.awaitSuspending()
    }

    override suspend fun get(key: String): ByteArray {
        val entity = Panache.withSession { repository.findById(key) }.awaitSuspending()
            ?: throw NoSuchElementException("No object stored under key '$key'")
        return entity.content
    }

    override suspend fun exists(key: String): Boolean =
        Panache.withSession { repository.findById(key) }.awaitSuspending() != null

    override suspend fun presignGet(key: String, ttlSeconds: Long): String = throw UnsupportedOperationException(
        "PostgresBlobStore has no native pre-signing capability (BYTEA is not an " +
            "object store with signed URLs). Fetch bytes via get(key) through this " +
            "service's own authenticated/authorized REST endpoint instead of expecting " +
            "a real time-boxed download URL on this backend.",
    )

    /**
     * Deliberately minimal, dependency-free JSON-object serialization — this module has no
     * Jackson dependency and the metadata map is expected to hold short, simple tokens (e.g.
     * checksums, content-disposition hints), never PII (ADR-0161 privacy rule) or arbitrary
     * user-controlled free text. Escapes only `"` and `\`.
     */
    private fun metadataToJson(metadata: Map<String, String>): String? {
        if (metadata.isEmpty()) return null
        return metadata.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "${jsonString(k)}:${jsonString(v)}"
        }
    }

    private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
