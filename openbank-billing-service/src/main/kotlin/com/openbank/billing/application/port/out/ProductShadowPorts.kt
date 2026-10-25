// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.application.port.out

import com.openbank.billing.domain.ProductShadowInput
import com.openbank.libs.productrevision.IrSignature
import java.util.UUID

/** A queued legacy decision, claimed by one comparator run. [attempts] counts earlier retries. */
data class QueuedShadowInput(val id: UUID, val input: ProductShadowInput, val attempts: Int)

/**
 * The local outbox of legacy decisions awaiting comparison (ADR-0352 §6). Rows are written in the
 * assessment's own transaction ([BillingAssessmentRepository.persistWithPostingIntent]) and read
 * here, off the posting path.
 */
interface ProductShadowQueuePort {
    /** Claims up to [limit] inputs that are due; a claim not completed within the lease is re-offered. */
    suspend fun claim(limit: Int): List<QueuedShadowInput>

    /** Terminal: the input was compared or definitively could not be. [outcome] is recorded for audit. */
    suspend fun complete(id: UUID, outcome: String)

    /** Transient failure: offered again after a back-off. */
    suspend fun retryLater(id: UUID, outcome: String)
}

/**
 * A pinned revision's product IR as billing keeps it locally (ADR-0352 §6: the consumer's own
 * immutable copy, keyed by the catalog revision billing pins). [irJson] and [signature] are null
 * exactly when [hasIr] is false; an IR-bearing revision with no signature is recorded as
 * `hasIr = true, signature = null` and never trusted.
 */
data class StoredRevisionIr(
    val revisionId: String,
    val hasIr: Boolean,
    val revisionHash: String?,
    val irJson: String?,
    val signature: IrSignature?,
)

/** Billing's immutable local copies of pinned revisions' IR; written once per revision id. */
interface RevisionIrCopyPort {
    suspend fun find(revisionId: String): StoredRevisionIr?

    /** Insert-if-absent; returns the stored row (an earlier writer's, if one raced). */
    suspend fun store(copy: StoredRevisionIr): StoredRevisionIr
}

/** The catalog could not answer (transport, HTTP status or an unreadable body); the input is retried. */
class CatalogRevisionUnavailableException(message: String, cause: Throwable) : RuntimeException(message, cause)

/** Reads a revision's IR and signature from product-catalog. Called only by the comparator, never on the posting path. */
interface CatalogRevisionIrPort {
    /** Throws [CatalogRevisionUnavailableException] when the catalog cannot answer. */
    suspend fun fetch(revisionId: String): StoredRevisionIr
}
