// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.domain.model

/**
 * How far one entry's own stored hash can be trusted, recomputed at read time (ADR-0214 D3).
 *
 * - [VERIFIED]: `record_hash` recomputes from the row as stored — the row has not been edited.
 * - [MISMATCH]: it does not — the row was edited after it was written. Evidence of tampering.
 * - [LEGACY_UNVERIFIABLE]: written with the pre-#3586 canonical form; can never be recomputed.
 *   Not evidence of tampering, and deliberately not merged with either of the above.
 * - [UNCHAINED]: written before the hash chain existed (V5); there is nothing to recompute.
 *
 * Per-entry recomputation proves each row is unaltered. Proving that no row was DELETED or
 * re-ordered needs the full chain walk, which is `GET /api/v1/audit/integrity`, not this.
 */
enum class EntryHashStatus { VERIFIED, MISMATCH, LEGACY_UNVERIFIABLE, UNCHAINED }

data class EvidenceEntry(
    val entry: AuditEntry,
    val recordHash: String?,
    val prevHash: String?,
    val hashStatus: EntryHashStatus,
)

/**
 * Every audit entry about one aggregate, oldest first, each with its recomputed hash status.
 * [truncated] is true when more entries exist than [EvidenceBundle.MAX_ENTRIES]: an evidence
 * bundle that silently drops its tail would read as complete, so it says so instead.
 */
data class EvidenceBundle(val aggregateId: String, val entries: List<EvidenceEntry>, val truncated: Boolean) {
    val tampered: Boolean get() = entries.any { it.hashStatus == EntryHashStatus.MISMATCH }

    companion object {
        const val MAX_ENTRIES: Int = 1_000
    }
}
