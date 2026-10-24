// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.marketdata.BatchStatus
import com.openbank.treasury.domain.marketdata.FixingCase
import com.openbank.treasury.domain.marketdata.FixingCaseStatus
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.ObservationBatch
import java.time.LocalDate
import java.util.UUID

class ObservationBatchNotFoundException(id: UUID) : NoSuchElementException("market-data batch $id not found")

/** Approving a batch would make a second value ACTIVE for an (index, date) that already has one. */
class ObservationConflictException(message: String) : IllegalStateException(message)

/** ADR-0344 D5: index observations, their four-eyes batches and the quality-gate cases. */
interface MarketDataRepository {
    /** Insert a new PENDING batch with its rows ([priorStatus] null) or store a decision on it (row locked). */
    suspend fun saveBatch(batch: ObservationBatch, priorStatus: BatchStatus?)

    suspend fun findBatch(batchId: UUID): ObservationBatch?

    suspend fun batches(status: BatchStatus?, limit: Int): List<ObservationBatch>

    /** ACTIVE observations of [index] dated in [from]..[to], oldest first. */
    suspend fun active(index: String, from: LocalDate, to: LocalDate): List<IndexObservation>

    /** ACTIVE observations of any (or one) index in [from]..[to], at most [limit], by index then date. */
    suspend fun history(index: String?, from: LocalDate, to: LocalDate, limit: Int): List<IndexObservation>

    /** Upsert by (index, fixing date). */
    suspend fun saveCase(case: FixingCase)

    suspend fun cases(fixingDate: LocalDate?, status: FixingCaseStatus?, limit: Int): List<FixingCase>
}

/** A flow due for fixing with what the fixing needs to know about its deal. */
data class DueFlow(val flow: CashFlow, val index: String, val dealVersion: Int)

interface FixingFlowRepository {
    /** Latest-schedule PROJECTED floating flows with fixing date [date], of live ACTUAL deals. */
    suspend fun dueOn(date: LocalDate): List<CashFlow>

    /** Latest-schedule flows FIXED from an observation dated [date]. */
    suspend fun fixedOn(date: LocalDate): List<CashFlow>

    /**
     * Store [flows] (each still PROJECTED, row locked — a concurrent fixing is refused) and the
     * outbox [event] in ONE transaction.
     */
    suspend fun applyFixing(dealId: UUID, flows: List<CashFlow>, event: DealEvent)
}

/**
 * The adapter port for a future market-data feed (ADR-0344 D5). Nothing here ingests the interbank
 * indices yet, so the deployed adapter returns nothing and observations arrive by four-eyes import.
 */
fun interface IndexFeed {
    /** Observations the feed publishes for [date]; they still pass through a PENDING batch. */
    suspend fun observations(date: LocalDate): List<Triple<String, LocalDate, java.math.BigDecimal>>
}
