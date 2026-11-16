// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.domain.model

import java.security.MessageDigest
import java.time.temporal.ChronoUnit
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
     * Canonical form 3: evidence id + `submittedAt` as a UTC instant truncated to microseconds,
     * plus this version number itself. Forms 1 and 2 are implicit (`hash_version` NULL) and are
     * never written again — they exist only as the marker on rows that predate V12:
     *  - form 1 hashed `submittedAt.toString()` (nanoseconds + zone offset), which a Postgres
     *    `TIMESTAMPTZ` round trip cannot reproduce (#16473);
     *  - form 2 (the #16511 window) hashed the microsecond instant but dropped the evidence id.
     * Same scheme as audit-service's `HASH_VERSION_MICROS` (#3586, ADR-0133 precedent), numbered
     * the same way: the first written version is one past the implicit forms.
     */
    const val HASH_VERSION_ID_MICROS: Short = 3

    /**
     * SHA-256 (hex) over the hash version, the previous link and every evidential field of
     * [evidence]. [evidence] must already carry the [DisputeEvidence.sequence],
     * [DisputeEvidence.prevHash], [DisputeEvidence.submittedAt] and
     * [DisputeEvidence.hashVersion] it will be persisted with — [recordHash] does not stamp them,
     * it only hashes what is passed in. Only [HASH_VERSION_ID_MICROS] can be computed; a legacy
     * (NULL) or unknown version is rejected, because no rendering of a stored legacy row
     * reproduces its original input.
     *
     * The version is part of the hashed input, so a stored row's version cannot be changed
     * without breaking its own hash — in particular it cannot be edited to some other number to
     * dodge recomputation. (Setting it to NULL is the other escape; [verify] handles that by
     * ordering, see there.)
     *
     * [DisputeEvidence.submittedAt] is hashed in a **round-trip-canonical** form: the UTC instant
     * truncated to microseconds, which is what `TIMESTAMPTZ` gives back (#16473).
     *
     * [DisputeEvidence.id] is hashed: it is assigned in the domain (`UUID.randomUUID()`) before
     * [append] stamps the item and persisted verbatim (no `@GeneratedValue`, the V1 column
     * default is never used), so the stamp-time id equals the read-back id and hashing it costs
     * no round-trip stability while making an id swap detectable.
     */
    fun recordHash(evidence: DisputeEvidence): String {
        require(evidence.hashVersion == HASH_VERSION_ID_MICROS) {
            "cannot compute evidence hash for hash version ${evidence.hashVersion}"
        }
        val canonical = listOf(
            "v${evidence.hashVersion}",
            evidence.prevHash ?: GENESIS_HASH,
            evidence.disputeId.toString(),
            evidence.sequence.toString(),
            evidence.id.toString(),
            evidence.submittedBy,
            evidence.evidenceType,
            evidence.description ?: "",
            evidence.fileReference ?: "",
            evidence.submittedAt?.toInstant()?.truncatedTo(ChronoUnit.MICROS)?.toString() ?: "",
        ).joinToString("|")
        return sha256Hex(canonical)
    }

    /**
     * Stamp [evidence] with the next [DisputeEvidence.sequence], [DisputeEvidence.prevHash]
     * (derived from [previous] — its STORED recordHash, legacy or not — or [GENESIS_HASH] if this
     * is the first item) and [HASH_VERSION_ID_MICROS], then compute its
     * [DisputeEvidence.recordHash]. Returns the fully-chained item ready to persist.
     */
    fun append(evidence: DisputeEvidence, previous: DisputeEvidence?): DisputeEvidence {
        val sequence = (previous?.sequence ?: -1) + 1
        val prevHash = previous?.recordHash ?: GENESIS_HASH
        val chained = evidence.copy(sequence = sequence, prevHash = prevHash, hashVersion = HASH_VERSION_ID_MICROS)
        return chained.copy(recordHash = recordHash(chained))
    }

    /**
     * Walk a dispute's evidence chain oldest-first (caller must pass items already ordered by
     * [DisputeEvidence.sequence] ascending). Any in-place edit, delete, or re-order of a
     * versioned item breaks the walk at the first affected item.
     *
     * **Legacy segment.** Items with no [DisputeEvidence.hashVersion] were stamped under a form
     * that cannot be recomputed from the stored row (see [HASH_VERSION_ID_MICROS]). They are never
     * re-stamped (stored tamper-evidence is not rewritten), so they are handled like audit-service
     * handles its pre-#3586 rows: counted in [EvidenceChainVerification.legacyItems], not in
     * `itemsChecked`, and their content is not recomputed. Two deliberate differences from
     * audit-service's `verifyChain`, both stricter:
     *  - audit-service re-anchors on the first verifiable row's OWN prevHash. Here the linkage
     *    is checked across the whole chain, legacy included: every item's prevHash must equal the
     *    previous item's STORED recordHash (both are stored strings, so this is round-trip stable
     *    for every form). That keeps a deleted, inserted or re-ordered legacy item detectable,
     *    and makes the first versioned item's prevHash commit to the last legacy item's hash —
     *    re-anchoring on the row's own prevHash would let the boundary be cut silently.
     *  - legacy items may only form a PREFIX. A NULL-version item after any versioned item is a
     *    downgrade (a versioned row edited to look legacy to escape recomputation) and reports
     *    broken.
     *
     * **Known limitations** (also in `docs/threat-models/openbank-dispute-service.md`):
     *  - The CONTENT of a legacy item is not verified: an in-place edit of a legacy item's fields
     *    that leaves its stored recordHash alone is not detectable. Editing its stored recordHash
     *    is detectable — the next item's prevHash no longer matches.
     *  - A downgrade of the ENTIRE versioned tail of a dispute (every versioned item set to NULL)
     *    is indistinguishable from a chain that was always legacy. The result then reads
     *    `itemsChecked = 0, legacyItems = N`, which is why the count is reported: `intact` with
     *    zero checked items vouches for nothing.
     *
     * `intact` therefore means: the linkage of the whole chain holds and every versioned item
     * recomputes. It never means a legacy item's content was verified.
     */
    @Suppress("ReturnCount")
    fun verify(disputeId: UUID, itemsOldestFirst: List<DisputeEvidence>): EvidenceChainVerification {
        var expectedPrev = GENESIS_HASH
        var checked = 0
        var legacy = 0
        var seenVersioned = false
        fun broken(item: DisputeEvidence) = EvidenceChainVerification(
            disputeId = disputeId,
            intact = false,
            itemsChecked = checked,
            firstBrokenEvidenceId = item.id,
            legacyItems = legacy,
        )
        for (item in itemsOldestFirst) {
            val stored = item.recordHash
            if (item.prevHash != expectedPrev || stored == null) return broken(item)
            if (item.hashVersion == null) {
                if (seenVersioned) return broken(item) // downgrade: legacy only as a prefix
                legacy++
            } else {
                seenVersioned = true
                if (item.hashVersion != HASH_VERSION_ID_MICROS || stored != recordHash(item)) return broken(item)
                checked++
            }
            expectedPrev = stored
        }
        return EvidenceChainVerification(
            disputeId = disputeId,
            intact = true,
            itemsChecked = checked,
            legacyItems = legacy,
        )
    }

    /** Mirrors `AuditRepository.sha256` (ADR-0133) byte-for-byte in output shape. */
    private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
