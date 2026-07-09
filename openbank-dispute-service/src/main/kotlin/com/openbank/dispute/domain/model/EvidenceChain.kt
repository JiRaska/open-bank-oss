// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.domain.model

import java.security.MessageDigest
import java.util.UUID

/**
 * Tamper-evident hash chain for [DisputeEvidence] (ADR-0117 hardening §2).
 *
 * Each dispute has its own chain (evidence is scoped to a case, unlike audit-service's single
 * global log): [recordHash] commits to the previous item's hash plus this item's own content, so
 * any in-place edit, delete, or re-order of a stored evidence row changes the recomputed hash and
 * is detected by [verify].
 *
 * **Why a bespoke chain and not a shared `openbank-libs` primitive**: at the time of writing, no
 * reusable hash-chain/tamper-evidence primitive exists in `openbank-libs` — `openbank-audit-service`
 * implements its own chain locally (`AuditRepository.chainHash`, ADR-0133), and
 * `openbank-libs`' `AnalyticsIntegrity` implements an unrelated Merkle-batch scheme for the
 * analytics bronze layer. Rather than invent a new shared abstraction (out of scope for this
 * first increment) or copy a global-log design that doesn't fit a per-dispute chain, this is a
 * small, self-contained, pure-domain implementation local to this service — the same "simple
 * append-only + monotonic sequence + hash of (previous-hash + content)" shape as ADR-0133,
 * scoped per dispute instead of globally. A future hardening pass can promote this to a shared
 * libs primitive once a second service needs the same shape.
 *
 * Pure and deterministic — zero framework imports (hexagonal architecture, ADR-0002).
 */
object EvidenceChain {

    /** Genesis previous-hash for the first evidence item of a dispute. */
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * SHA-256 (hex) over the previous link and every evidential field of [evidence]. [evidence]
     * must already carry the [DisputeEvidence.sequence], [DisputeEvidence.prevHash], and
     * [DisputeEvidence.submittedAt] it will be persisted with — [recordHash] does not stamp them,
     * it only hashes what is passed in.
     */
    fun recordHash(evidence: DisputeEvidence): String {
        val canonical = listOf(
            evidence.prevHash ?: GENESIS_HASH,
            evidence.disputeId.toString(),
            evidence.sequence.toString(),
            evidence.id.toString(),
            evidence.submittedBy,
            evidence.evidenceType,
            evidence.description ?: "",
            evidence.fileReference ?: "",
            evidence.submittedAt?.toString() ?: "",
        ).joinToString("|")
        return sha256Hex(canonical)
    }

    /**
     * Stamp [evidence] with the next [DisputeEvidence.sequence] and [DisputeEvidence.prevHash]
     * (derived from [previous], or [GENESIS_HASH] if this is the first item), then compute its
     * [DisputeEvidence.recordHash]. Returns the fully-chained item ready to persist.
     */
    fun append(evidence: DisputeEvidence, previous: DisputeEvidence?): DisputeEvidence {
        val sequence = (previous?.sequence ?: -1) + 1
        val prevHash = previous?.recordHash ?: GENESIS_HASH
        val chained = evidence.copy(sequence = sequence, prevHash = prevHash)
        return chained.copy(recordHash = recordHash(chained))
    }

    /**
     * Walk a dispute's evidence chain oldest-first (caller must pass items already ordered by
     * [DisputeEvidence.sequence] ascending) and recompute every link. Any in-place edit, delete,
     * or re-order breaks the recomputation at the first affected item.
     */
    fun verify(disputeId: UUID, itemsOldestFirst: List<DisputeEvidence>): EvidenceChainVerification {
        var expectedPrev = GENESIS_HASH
        var checked = 0
        for (item in itemsOldestFirst) {
            val recomputed = recordHash(item)
            val broken = item.prevHash != expectedPrev || item.recordHash != recomputed
            if (broken) {
                return EvidenceChainVerification(
                    disputeId = disputeId,
                    intact = false,
                    itemsChecked = checked,
                    firstBrokenEvidenceId = item.id,
                )
            }
            expectedPrev = recomputed
            checked++
        }
        return EvidenceChainVerification(disputeId = disputeId, intact = true, itemsChecked = checked)
    }

    /** Mirrors `AuditRepository.sha256` (ADR-0133) byte-for-byte in output shape. */
    private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
