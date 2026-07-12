// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Ops recovery: re-emit historical `AccountBookedChanged` events for a date window so a downstream
 * projection that missed them (e.g. balance-service's sub-ledger, issue #860) can catch up.
 *
 * The ledger is the authoritative source; this replays its own already-posted booked movements — it
 * posts NO new journal and mutates NO ledger state. The re-emitted events are reconstructed exactly as
 * the original post emitted them (`JournalEntry.bookedDeltas()`), so a consumer that dedups on the
 * business key `(journalEntryId, accountId, currency)` applies only the genuinely-missing deltas.
 *
 * `dryRun` (default true) previews the counts + net delta per currency WITHOUT emitting anything.
 */
data class ReplayBookedChangesCommand(val from: LocalDate, val to: LocalDate, val dryRun: Boolean = true)

data class ReplayBookedChangesResult(
    val dryRun: Boolean,
    val from: LocalDate,
    val to: LocalDate,
    /** POSTED journal entries scanned in the window. */
    val journalEntriesScanned: Int,
    /** AccountBookedChanged events that were (or, in a dry run, would be) re-emitted. */
    val bookedChangeEvents: Int,
    /** Distinct customer accounts touched. */
    val accountsTouched: Int,
    /** Net booked delta per currency across the window — a sanity preview of what the replay carries. */
    val netDeltaByCurrency: Map<String, BigDecimal>,
)

interface ReplayBookedChangesUseCase {
    suspend fun replay(command: ReplayBookedChangesCommand): ReplayBookedChangesResult
}
