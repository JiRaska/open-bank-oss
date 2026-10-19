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

    /** All writes and the command key in ONE transaction. Throws IllegalStateException on a stale prior status. */
    suspend fun save(writes: List<ReferenceWrite>, command: ReferenceCommandKey?)

    suspend fun findCommand(key: String): ReferenceCommandKey?
}

/** What a deal names, for the reference dependency query. */
fun interface DealReferenceLookup {
    /** Deals (any state) whose terms name [ref]: counterparty, book, calendar, index or security. */
    suspend fun dealsReferencing(ref: ReferenceRef): List<UUID>
}
