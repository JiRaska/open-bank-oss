// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.feedback

import com.openbank.libs.storage.ObjectStorePort
import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Object-storage side of screen feedback (ADR-0192): the confirmed screenshot goes to a
 * bucket, only its key goes onto Kafka.
 *
 * Uses the shared [ObjectStorePort] (ADR-0161) rather than a bespoke S3 client, so bucket,
 * region, SSE and credentials stay a configuration/Terraform concern. The edge selects the
 * `s3` backend at build time (`openbank.objectstore.backend` in application.yaml) — it has no
 * database, so the Postgres adapter is not an option here.
 *
 * The key is deliberately opaque and PII-free (`customer-edge/feedback/<uuid>.png`, the
 * ADR-0161 key rule): the party a screenshot belongs to is recorded in the event/warehouse row,
 * not in the object name, so bucket listings leak nothing. Retention is a bucket lifecycle rule
 * (90 days, ADR-0192), never enforced from application code.
 *
 * [store] is fail-soft ON PURPOSE. The user already consented and pressed send; if object
 * storage is unavailable (in sandbox it is not wired at all until the bucket + Pod Identity
 * association exist), losing their written comment too would be strictly worse than losing the
 * image. A failed write yields [Result.status] = `STORE_FAILED`, which rides on the event so the
 * warehouse can tell "text-only submission" apart from "we dropped the picture".
 */
@ApplicationScoped
class FeedbackScreenshotStore(private val objectStore: ObjectStorePort) {

    /** Outcome of a screenshot write. [key] is null unless [status] is [STATUS_STORED]. */
    data class Result(val key: String?, val status: String)

    /**
     * Writes [png] under a fresh key derived from [feedbackId] and returns its key.
     * Never throws: a storage failure degrades to [STATUS_STORE_FAILED].
     */
    @Suppress("TooGenericExceptionCaught") // any storage failure must degrade, not 5xx the caller
    fun store(feedbackId: UUID, reference: String, png: ByteArray): Result {
        val key = "$KEY_PREFIX/$feedbackId.png"
        return try {
            // ObjectStorePort is a suspend API and this runs on a @Blocking JAX-RS worker thread,
            // so a plain runBlocking is correct here — there is no event-loop thread to starve.
            runBlocking {
                objectStore.put(
                    key = key,
                    bytes = png,
                    contentType = PNG_CONTENT_TYPE,
                    // Reference only — enough to trace an object back to its warehouse row during
                    // an erasure request, without stamping the party id onto the object itself.
                    metadata = mapOf("reference" to reference),
                )
            }
            Result(key, STATUS_STORED)
        } catch (e: Exception) {
            Log.error("feedback screenshot store failed for $reference (key=$key): ${e.message}", e)
            Result(null, STATUS_STORE_FAILED)
        }
    }

    companion object {
        const val STATUS_NONE = "NONE"
        const val STATUS_STORED = "STORED"
        const val STATUS_STORE_FAILED = "STORE_FAILED"

        private const val KEY_PREFIX = "customer-edge/feedback"
        private const val PNG_CONTENT_TYPE = "image/png"

        /** PNG file signature (RFC 2083 §3.1) — the content-type check that actually inspects bytes. */
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /**
         * True when [bytes] really is a PNG. The client declares the field name, not the format:
         * without this, `screenshotPngBase64` is an arbitrary-bytes upload channel into a bucket.
         */
        fun isPng(bytes: ByteArray): Boolean =
            bytes.size > PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }
    }
}
