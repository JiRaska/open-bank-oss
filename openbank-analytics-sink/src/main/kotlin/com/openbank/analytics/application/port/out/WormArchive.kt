// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.application.port.out

import java.time.Instant

/**
 * A sealed tamper-evidence anchor: a Merkle root over a batch of bronze record hashes, plus the
 * previous anchor's hash so the sequence of anchors is itself a chain (ADR-0023, finding F1+F2).
 *
 * Only this small structure (a few hundred bytes per batch) needs to live in genuinely immutable
 * storage — the bronze rows stay in ClickHouse (cheap, queryable, but operator-mutable). Any later
 * challenge re-derives the leaf hashes from bronze and checks they still produce [merkleRoot]; if a
 * row was altered or dropped, the recomputed root will not match the sealed one.
 */
data class IntegrityAnchor(
    val anchorId: String,
    /** Merkle root over the batch's record hashes (see AnalyticsIntegrity.merkleRoot). */
    val merkleRoot: String,
    /** Hash of the previous anchor, chaining anchors into a tamper-evident timeline. Null for the first. */
    val previousAnchorHash: String?,
    val recordCount: Int,
    val source: String,
    val sealedAt: Instant
)

/**
 * Outbound port to **write-once-read-many** storage that holds the integrity anchors (and, optionally,
 * sealed reconciliation evidence). Real bindings target S3 Object Lock (compliance mode), an append-only
 * ledger, or the existing audit service — anything an operator with warehouse access **cannot** rewrite.
 *
 * The default binding is [com.openbank.analytics.infrastructure.worm.LoggingWormArchive] (logs the
 * anchor, no infra) so the service stays offline-buildable; the durable S3-Object-Lock adapter is the
 * documented `@Alternative @Priority(...)` follow-up, mirroring the LoggingAuditEventPublisher pattern.
 *
 * Sealing must **never silently fail**: a missing WORM target degrades tamper-*evidence*, not the
 * bronze write itself, but it must be logged loudly so the gap is visible.
 */
interface WormArchive {

    /** Seals one [IntegrityAnchor]. Implementations must be append-only (no overwrite/delete). */
    suspend fun seal(anchor: IntegrityAnchor)

    /** Returns the most recently sealed anchor, for chaining the next one. Null if none yet. */
    suspend fun latest(): IntegrityAnchor?
}
