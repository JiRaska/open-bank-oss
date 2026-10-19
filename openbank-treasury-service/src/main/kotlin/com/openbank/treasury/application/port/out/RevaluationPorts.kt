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

interface RevaluationRepository {
    suspend fun find(dealId: UUID, asOf: LocalDate): RevaluationRecord?

    /** The cumulative adjustment of the latest revaluation BEFORE [asOf]; zero when none. */
    suspend fun cumulativeBefore(dealId: UUID, asOf: LocalDate): BigDecimal

    suspend fun forDate(asOf: LocalDate, limit: Int): List<RevaluationRecord>

    /**
     * Store [record] and, when it posted, its journal reference — ONE transaction. Unique on
     * (deal, date): the loser of a concurrent replay gets false and nothing is written twice.
     */
    suspend fun save(record: RevaluationRecord, journal: LedgerJournalRef?): Boolean
}
