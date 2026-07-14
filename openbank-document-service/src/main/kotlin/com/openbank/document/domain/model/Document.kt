// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Lifecycle of a rendered document artifact. */
enum class DocumentStatus { GENERATED, PENDING_SIGNATURE, SIGNED, ARCHIVED }

/**
 * A rendered, content-addressed document artifact and its lifecycle/retention metadata. The bytes
 * themselves live in the object store under [storageKey]; this aggregate holds the metadata and the
 * [sha256] content digest. Pure domain aggregate: no framework imports (ADR-0002).
 */
data class Document(
    val id: UUID,
    val templateCode: String,
    val templateVersion: String,
    val sha256: String,
    val storageKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val status: DocumentStatus,
    val metadata: Map<String, String>,
    val partyRef: String?,
    val caseRef: String?,
    val productRef: String?,
    val retainUntil: LocalDate?,
    val createdAt: Instant,
    // Onboarding-specific idempotency key ("onboarding:<accountId>"), null for every other document
    // type. Backed by a partial unique index (V6) so at-least-once event redelivery cannot render a
    // second onboarding contract for the same account (ADR-0162 D7). Not part of the content address.
    val idempotencyKey: String? = null,
) {
    fun markPendingSignature(): Document {
        require(status == DocumentStatus.GENERATED) { "Only GENERATED documents can enter signing" }
        return copy(status = DocumentStatus.PENDING_SIGNATURE)
    }

    fun markSigned(): Document {
        require(status == DocumentStatus.PENDING_SIGNATURE) { "Only PENDING_SIGNATURE documents can be signed" }
        return copy(status = DocumentStatus.SIGNED)
    }

    fun archive(): Document {
        require(status != DocumentStatus.ARCHIVED) { "Document is already ARCHIVED" }
        return copy(status = DocumentStatus.ARCHIVED)
    }

    companion object {
        /** Lower-case hex SHA-256 of the given bytes — the document's content address. */
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
