// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One deal's fair value from the risk engine's valuation; [unpriced] names why it has none. */
data class DealFairValue(val dealId: UUID, val pv: BigDecimal?, val clean: BigDecimal?, val unpriced: String?)

/** The risk engine's valuation of the own book on one snapshot (ADR-0344 D8), in [currency]. */
data class BookValuation(
    val snapshotId: UUID,
    val asOf: LocalDate,
    val currency: String,
    val inputHash: String?,
    val deals: Map<UUID, DealFairValue>,
    /** Set when the engine itself could not value the whole book. */
    val dataGap: String?,
)

/** The risk engine answered with an error, or could not be reached: a data gap, never a zero. */
class ValuationUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Read-only access to the risk engine's valuation. Never prices anything itself. */
interface ValuationPort {
    /** The newest TIED_OUT snapshot's date, or null when the engine has none. */
    suspend fun latestAsOf(): LocalDate?

    /** The valuation of the newest TIED_OUT snapshot as of [asOf], or null when there is none. */
    suspend fun valuation(asOf: LocalDate): BookValuation?

    companion object {
        /** No risk engine wired: every revaluation is a data gap. */
        val NONE = object : ValuationPort {
            override suspend fun latestAsOf(): LocalDate? = null

            override suspend fun valuation(asOf: LocalDate): BookValuation? = null
        }
    }
}

/**
 * One deal's revaluation on one snapshot date: the fair value the engine reported, the carrying
 * amount it was measured against, the cumulative adjustment after it and the change this date posted.
 */
data class RevaluationRecord(
    val dealId: UUID,
    val asOf: LocalDate,
    val snapshotId: UUID,
    val fairValue: BigDecimal,
    val carrying: BigDecimal,
    val cumulative: BigDecimal,
    val change: BigDecimal,
    val idempotencyKey: String?,
    val journalId: UUID?,
    val recordedAt: Instant,
)

/**
 * A revaluation of [asOf] was asked for while the deal already holds one for [latest] ≥ [asOf] that it
 * does not exactly replay (409 `REVALUATION_OUT_OF_ORDER`). Revaluations are strictly increasing in
 * date per deal: an earlier date is never back-filled — the next date's change covers it.
 */
class RevaluationOutOfOrderException(val dealId: UUID, val asOf: LocalDate, val latest: LocalDate) :
    RuntimeException("deal $dealId is revalued through $latest; $asOf is not after it and is not a replay")

/**
 * The deal's latest revaluation ([owedAsOf]) is claimed but its journal was never posted (409
 * `PRIOR_REVALUATION_UNPOSTED`): a new date would measure its change against a posting that never
 * happened. The owed row is re-posted first — by its own key and stored change — then the date runs.
 */
class PriorRevaluationUnpostedException(val dealId: UUID, val asOf: LocalDate, val owedAsOf: LocalDate) :
    RuntimeException("deal $dealId has an unposted revaluation of $owedAsOf; $asOf waits until it is posted")

/** The owed (claimed, unposted) revaluations: how many, and the oldest claim time. */
data class UnpostedRevaluations(val count: Int, val oldestClaimedAt: Instant?)

/** What [RevaluationRepository.claim] found. */
sealed interface RevaluationClaim {
    val record: RevaluationRecord

    /** A new row: its change is owed to the ledger (when non-zero) under [RevaluationRecord.idempotencyKey]. */
    data class Claimed(override val record: RevaluationRecord) : RevaluationClaim

    /** The same (deal, date, snapshot, fair value) was claimed before: an exact replay. */
    data class Existing(override val record: RevaluationRecord) : RevaluationClaim
}

interface RevaluationRepository {
    suspend fun find(dealId: UUID, asOf: LocalDate): RevaluationRecord?

    suspend fun forDate(asOf: LocalDate, limit: Int): List<RevaluationRecord>

    /**
     * Under a per-deal transaction lock: an exact replay of (deal, [asOf], snapshot, fair value) — or
     * ANY retry of a row still owed to the ledger, which re-posts its own stored change — returns
     * [RevaluationClaim.Existing]; a new date while the latest row is owed throws
     * [PriorRevaluationUnpostedException]; a date not after the deal's latest revaluation throws
     * [RevaluationOutOfOrderException]; otherwise the change against the latest cumulative
     * adjustment is computed IN the lock and the row is inserted (no journal yet), so two concurrent
     * runs can never both measure against the same prior. [keyOf] names the ledger key a change owes.
     */
    @Suppress("LongParameterList")
    suspend fun claim(
        dealId: UUID,
        asOf: LocalDate,
        snapshotId: UUID,
        fairValue: BigDecimal,
        carrying: BigDecimal,
        target: BigDecimal,
        keyOf: (change: BigDecimal) -> String?,
        at: Instant,
    ): RevaluationClaim

    /** Rows claimed with a change owed to the ledger but no journal yet, oldest first, at most [limit]. */
    suspend fun owed(limit: Int): List<RevaluationRecord>

    suspend fun unposted(): UnpostedRevaluations

    /** Record the ledger journal of a claimed row and its deal journal reference — ONE transaction. */
    suspend fun attachJournal(dealId: UUID, asOf: LocalDate, journal: LedgerJournalRef)
}
