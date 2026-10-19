// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.reference.ReferenceKind
import com.openbank.treasury.domain.reference.ReferenceRecord
import com.openbank.treasury.domain.reference.ReferenceRef
import com.openbank.treasury.domain.reference.ReferenceStatus
import java.time.LocalDate
import java.util.UUID

/** A reference command's `Idempotency-Key`, stored with the change it produced (same transaction). */
data class ReferenceCommandKey(
    val key: String,
    val action: String,
    val kind: ReferenceKind,
    val recordKey: String,
    val version: Int,
)

/**
 * One record to write, with the status the store must still hold for it ([priorStatus], null for a
 * new version). A mismatch means another request changed it first: the whole save is refused (409)
 * rather than overwriting a decision someone else just made.
 */
data class ReferenceWrite(val record: ReferenceRecord, val priorStatus: ReferenceStatus?)

interface ReferenceRepository {
    suspend fun list(kind: ReferenceKind, status: ReferenceStatus?, asOf: LocalDate?): List<ReferenceRecord>

    /** Every version of one record, oldest first. */
    suspend fun versions(kind: ReferenceKind, key: String): List<ReferenceRecord>

    /** Every PENDING or ACTIVE record of every kind (the dependency graph is small by nature). */
    suspend fun live(): List<ReferenceRecord>

    /** The subset of [refs] with no ACTIVE version effective on [on]. */
    suspend fun notEffective(refs: Collection<ReferenceRef>, on: LocalDate): List<ReferenceRef>

    /** A NEW version (prior status null) and its command key, in one transaction. */
    suspend fun save(writes: List<ReferenceWrite>, command: ReferenceCommandKey?)

    /**
     * Decide on an existing record under lock: every version of (kind, key) is read `FOR UPDATE`,
     * [decide] runs on that locked state (so supersession is resolved against what is really
     * stored, never a snapshot read before the lock), and its writes are stored — each checked
     * against the locked row's full prior state and an optimistic `row_version` — with the command
     * key, all in ONE transaction. A concurrent decision therefore waits, then sees the outcome and
     * is refused (409); it never overwrites it.
     */
    suspend fun decide(
        kind: ReferenceKind,
        key: String,
        command: ReferenceCommandKey,
        decide: (List<ReferenceRecord>) -> List<ReferenceWrite>,
    ): List<ReferenceRecord>

    suspend fun findCommand(key: String): ReferenceCommandKey?
}

/** What a deal names, for the reference dependency query. */
fun interface DealReferenceLookup {
    /** Deals (any state) whose terms name [ref]: counterparty, book, calendar, index or security. */
    suspend fun dealsReferencing(ref: ReferenceRef): List<UUID>
}
