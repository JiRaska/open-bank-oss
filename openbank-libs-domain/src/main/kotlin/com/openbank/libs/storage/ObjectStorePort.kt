// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.storage

/**
 * Shared, framework-free contract for storing and retrieving binary application
 * artifacts (documents, generated PDFs, uploaded evidence) — ADR-0161.
 *
 * This port is the *only* sanctioned way a service reads or writes a binary
 * artifact. Keys are opaque and service-namespaced (`<service>/<aggregate>/<uuid>`)
 * and MUST NOT contain personal data (ADR-0161 privacy rule: no PII in URLs/keys).
 * No bucket names, credentials, or backend-specific detail leak into this port —
 * those live entirely in the adapter implementation and its configuration.
 *
 * Two adapters ship in `openbank-libs-runtime` (ADR-0161 D2), selected at build
 * time via the single config key `openbank.objectstore.backend: s3|postgres`:
 *  - `S3ObjectStore` — production, backed by AWS S3 (Object Lock + SSE-at-rest).
 *  - `PostgresBlobStore` — dev/test/low-volume, backed by a per-service `BYTEA` table.
 *
 * See docs/adr/0161-object-storage-standard-for-application-documents.md.
 */
interface ObjectStorePort {

    /**
     * Writes [bytes] under [key] with the given [contentType] and optional
     * [metadata]. Overwrites any existing value at [key] unless the backing
     * adapter enforces write-once semantics (e.g. S3 Object Lock in COMPLIANCE
     * mode on the production adapter).
     */
    suspend fun put(key: String, bytes: ByteArray, contentType: String, metadata: Map<String, String> = emptyMap())

    /** Reads the bytes stored under [key]. Throws if no object exists at [key]. */
    suspend fun get(key: String): ByteArray

    /** Returns `true` if an object is currently stored under [key]. */
    suspend fun exists(key: String): Boolean

    /**
     * Returns a time-boxed download URL for [key], valid for [ttlSeconds] seconds.
     * Not every adapter can produce a true, unauthenticated pre-signed URL — see
     * the implementing adapter's own KDoc for how it honors (or cannot honor)
     * this contract before assuming the returned URL behaves identically across
     * backends.
     */
    suspend fun presignGet(key: String, ttlSeconds: Long): String
}
