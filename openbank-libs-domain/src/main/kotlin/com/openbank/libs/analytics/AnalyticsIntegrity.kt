// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

import java.security.MessageDigest

/**
 * Tamper-evidence for the analytics bronze layer (ADR-0022 / ADR-0023, finding F1+F2).
 *
 * A regulator (CNB/EBA, BCBS 239 §3 "accuracy & integrity") will not accept "the warehouse is the
 * record of truth" unless we can *prove* a row was not altered or silently dropped after it was
 * written. ClickHouse `ReplacingMergeTree` rows are physically mutable by an operator with table
 * access, so bronze on its own is **not** a legal record — it is a derived, rebuildable projection.
 *
 * This object makes bronze *tamper-evident* and lets the immutable proof live in a cheap, small,
 * WORM-anchored side-structure rather than requiring an expensive append-only store for every row:
 *
 *  - [recordHash] — a deterministic SHA-256 over the canonical form of an envelope's identity +
 *    business content. Stored as a bronze column; recomputable any time to detect row mutation.
 *  - [merkleRoot] — a single hash that commits to an *entire batch* of record hashes. Only the root
 *    (a few hundred bytes per batch) needs to be sealed in the WORM archive / audit trail; any later
 *    challenge re-derives the leaf hashes from bronze and checks they still produce the sealed root.
 *
 * Why Merkle-per-batch and not a strict global hash-chain: bronze ingestion is **parallel** (many
 * partitions, out-of-order, at-least-once). A single linked chain would force a global serialization
 * point and break under replay/backfill. A Merkle root over each ingest batch is order-independent
 * within the batch (leaves are sorted), parallel-friendly, and still gives single-value tamper
 * detection. Batch roots are themselves chained in the anchor store for a tamper-evident timeline.
 *
 * Pure and deterministic — no framework, unit-tested like the other analytics primitives.
 */
object AnalyticsIntegrity {

    /**
     * Canonical, stable string form of an envelope's *integrity-relevant* content. Two envelopes
     * with the same identity and business meaning always produce the same string regardless of map
     * ordering or in-memory representation, so [recordHash] is reproducible years later.
     *
     * Deliberately excludes [AnalyticsEnvelope.ingestedAt] and lineage ([AnalyticsEnvelope.ingestSource],
     * [AnalyticsEnvelope.batchId]): those describe *how/when* the row was loaded, not *what the event
     * is*. A correction that re-loads the same event under a new batch must hash identically so dedupe
     * and reconciliation stay consistent. The payload is included so any post-write tampering of the
     * (already PII-masked) body is detectable.
     */
    fun canonical(envelope: AnalyticsEnvelope): String = buildString {
        append(envelope.eventId).append('|')
        append(envelope.aggregateType).append('|')
        append(envelope.aggregateId).append('|')
        append(envelope.aggregateVersion).append('|')
        append(envelope.eventType).append('|')
        append(envelope.occurredAt.toEpochMilli()).append('|')
        append(envelope.sourceService).append('|')
        append(envelope.schemaVersion).append('|')
        append(envelope.synthetic).append('|')
        append(canonicalPayload(envelope.payload))
    }

    /** Deterministic SHA-256 (hex) over [canonical]. The per-row tamper-evidence fingerprint. */
    fun recordHash(envelope: AnalyticsEnvelope): String = sha256Hex(canonical(envelope).toByteArray(Charsets.UTF_8))

    /** Deterministic SHA-256 (hex) over an arbitrary canonical string (e.g. reconciliation evidence). */
    fun recordHashOfString(canonical: String): String = sha256Hex(canonical.toByteArray(Charsets.UTF_8))

    /**
     * Merkle root (hex) committing to a set of leaf hashes. Leaves are **sorted** first so the root is
     * independent of ingest order within a batch (bronze is at-least-once and parallel). An empty
     * batch yields the hash of the empty string so a root is always defined.
     *
     * Construction: standard binary Merkle tree; an odd node at a level is promoted (duplicated) to
     * pair with itself. Returns the single root hash.
     */
    fun merkleRoot(leafHashes: List<String>): String {
        if (leafHashes.isEmpty()) return sha256Hex(ByteArray(0))
        var level = leafHashes.sorted()
        while (level.size > 1) {
            val next = ArrayList<String>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = if (i + 1 < level.size) level[i + 1] else left
                next.add(sha256Hex((left + right).toByteArray(Charsets.UTF_8)))
                i += 2
            }
            level = next
        }
        return level[0]
    }

    /** Convenience: Merkle root directly over a batch of envelopes (hashes each, then roots them). */
    fun merkleRootOf(envelopes: List<AnalyticsEnvelope>): String = merkleRoot(envelopes.map { recordHash(it) })

    // --- internals -------------------------------------------------------------------------------

    /** Stable serialization of the (possibly nested) masked payload: keys sorted, recursive. */
    private fun canonicalPayload(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> ->
            value.entries
                .map { (k, v) -> k.toString() to v }
                .sortedBy { it.first }
                .joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) -> "$k=${canonicalPayload(v)}" }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalPayload(it) }
        else -> value.toString()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
