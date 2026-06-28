// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.domain.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * A signed checkpoint over the audit hash chain (ADR-0031 D5).
 *
 * The V5 chain (`record_hash`/`prev_hash`) proves the log is internally consistent. It cannot,
 * on its own, detect a wholesale rewrite — an attacker who re-writes every row recomputes a
 * valid chain. An anchor closes that gap: it captures the chain head ([lastEntryId],
 * [lastRecordHash]) at a point in time and is signed by an external key, so the attestation
 * survives even if the database is later rewritten.
 */
data class AuditAnchor(
    val lastEntryId: UUID?,
    val lastRecordHash: String?,
    val chainedCount: Long,
    val chainStatus: String,
    val anchorDigest: String,
    val signature: String?,
    val keyId: String,
    val signedAt: Instant,
) {
    companion object {
        /**
         * Canonical SHA-256 digest over the attested checkpoint fields. Pure and deterministic so
         * it can be recomputed at verification time and compared against the stored value — any
         * edit to a stored anchor row changes the digest and invalidates its signature.
         */
        fun digest(
            lastEntryId: UUID?,
            lastRecordHash: String?,
            chainedCount: Long,
            chainStatus: String,
            signedAt: Instant,
        ): String {
            val canonical = listOf(
                lastEntryId?.toString() ?: "",
                lastRecordHash ?: "",
                chainedCount.toString(),
                chainStatus,
                signedAt.toEpochMilli().toString(),
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
