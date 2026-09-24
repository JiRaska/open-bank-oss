// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.port.out

import com.openbank.risk.domain.curve.CurveIndex
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.MoneyMarketQuote
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.LedgerInputs
import com.openbank.risk.domain.model.LoanContract
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.SnapshotRun
import com.openbank.risk.domain.model.TieOutMismatch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Reads the two ledger views a snapshot is built from. */
interface LedgerPort {
    suspend fun read(asOf: LocalDate): LedgerInputs
}

/**
 * Reads lending's loan book at snapshot time (ADR-0314 D4). A PULL, not an event consumer: no
 * event carries a loan's remaining schedule — see [com.openbank.risk.domain.model.LoanContract].
 */
interface LendingPort {
    suspend fun readLoanBook(asOf: LocalDate): List<LoanContract>
}

interface SnapshotRepository {
    suspend fun findByNaturalKey(asOf: LocalDate, inputHash: String): SnapshotRun?

    suspend fun findById(id: UUID): SnapshotRun?

    /**
     * Stores the run, its positions and its instruments in ONE transaction, idempotently on
     * (asOf, inputHash). Returns the run that is stored afterwards — [run] itself, or the one a
     * concurrent request committed first under the same natural key.
     */
    suspend fun saveIfAbsent(
        run: SnapshotRun,
        positions: List<Position>,
        instruments: List<Instrument> = emptyList(),
    ): SnapshotRun

    suspend fun findPositions(runId: UUID): List<Position>

    suspend fun findInstruments(runId: UUID): List<Instrument>
}

/** One currency rate from a published central-bank fixing (ADR-0314 D5). */
data class FxFixingRate(
    val source: String,
    val fixingDate: LocalDate,
    val currency: String,
    val quoteCurrency: String,
    val ratePerUnit: BigDecimal,
    val rateId: UUID,
    val validFrom: Instant,
    val validTo: Instant,
    val receivedAt: Instant,
)

interface FxFixingRepository {
    /** Inserts each rate unless (source, fixingDate, currency) exists. Returns how many were new. */
    suspend fun insertIfAbsent(rates: List<FxFixingRate>): Int
}

class SnapshotNotFoundException(id: UUID) : RuntimeException("snapshot run $id not found")

/** An UNTIED run is stored and flagged, never rendered (ADR-0314 D3). */
class UntiedSnapshotException(val runId: UUID, val mismatches: List<TieOutMismatch>) :
    RuntimeException("snapshot run $runId did not tie out to the ledger (${mismatches.size} mismatches)")

interface CurveSetRepository {
    /** Stores the set, its input quotes and its bootstrapped pillars in one transaction. */
    suspend fun save(set: CurveSet, quotes: Map<CurveIndex, List<MoneyMarketQuote>>)

    suspend fun findById(id: UUID): CurveSet?
}

class CurveSetNotFoundException(id: UUID) : RuntimeException("curve set $id not found")
