// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.application.port.out

import com.openbank.interest.domain.shadow.ShadowLegacyDecision
import com.openbank.interest.domain.shadow.ShadowSourceKind
import com.openbank.libs.productrevision.IrSignature
import com.openbank.libs.productrevision.RevisionIdentity
import java.math.BigDecimal
import java.util.UUID

/** A queued shadow input, claimed by one comparator run. [attempts] counts earlier retries. */
data class QueuedShadowInput(val id: UUID, val kind: ShadowSourceKind, val sourceId: UUID, val attempts: Int)

/** What a completed comparison leaves on its queue row: the outcome and, when compared, both amounts. */
data class ShadowCompletion(
    val outcome: String,
    val ruleId: String? = null,
    val legacyAmount: BigDecimal? = null,
    val irAmount: BigDecimal? = null,
)

/**
 * The interest shadow's queue (ADR-0352 §6). Unlike billing, nothing is written in the posting
 * transaction: [intake] enqueues committed accrual and capitalisation rows that have no queue row
 * yet, so the shadow cannot slow, fail or roll back a posting, and a rolled-back posting leaves no
 * row to enqueue.
 */
interface ProductShadowQueuePort {
    /** Enqueues up to [limit] committed legacy rows created within the last [lookbackDays]; returns how many. */
    suspend fun intake(lookbackDays: Long, limit: Int): Int

    /** Claims up to [limit] due inputs; a claim not completed within the lease is offered again. */
    suspend fun claim(limit: Int): List<QueuedShadowInput>

    suspend fun complete(id: UUID, completion: ShadowCompletion)

    /** Transient failure: offered again after a back-off. */
    suspend fun retryLater(id: UUID, outcome: String)
}

/** Reads the committed legacy row a queue input points at, with its account's revision pin. */
interface ShadowLegacySourcePort {
    /** Null when the row no longer exists. */
    suspend fun load(kind: ShadowSourceKind, sourceId: UUID): ShadowLegacyDecision?
}

/**
 * A pinned revision's product IR as interest keeps it locally (ADR-0352 §6: the consumer's own
 * immutable copy). [irJson] and [signature] are null when the revision carries no IR. [identity] is the
 * full signed envelope identity the catalog reported (#13749), null when it was incomplete, and such a
 * copy is never trusted. [contentHash] is the catalog's content hash of the revision, matched against
 * the hash interest recorded when it pinned the account.
 */
data class StoredRevisionIr(
    val revisionId: String,
    val hasIr: Boolean,
    val revisionHash: String?,
    val irJson: String?,
    val signature: IrSignature?,
    val identity: RevisionIdentity? = null,
    val contentHash: String? = null,
)

/** Interest's immutable local copies of revision IR, keyed by catalog revision id. */
interface RevisionIrCopyPort {
    suspend fun find(revisionId: String): StoredRevisionIr?

    /** Insert-if-absent; returns the stored copy (another pod's, if it won the race). */
    suspend fun store(copy: StoredRevisionIr): StoredRevisionIr
}

/** The catalog answered with a revision other than the one asked for. Never stored, never retried. */
class CatalogRevisionMismatchException(message: String) : RuntimeException(message)

class CatalogRevisionUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Reads a revision's IR and signature from product-catalog. Called only by the comparator, never on the posting path. */
interface CatalogRevisionIrPort {
    /** Throws [CatalogRevisionUnavailableException] when the catalog cannot answer. */
    suspend fun fetch(revisionId: String): StoredRevisionIr
}
