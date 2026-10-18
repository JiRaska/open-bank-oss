// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.clearing.domain.model.ClearingBatch
import com.openbank.clearing.domain.model.ClearingCycleResult
import com.openbank.clearing.domain.model.ClearingItem
import com.openbank.clearing.domain.model.PaymentRail
import java.math.BigDecimal
import java.util.UUID

/**
 * One net-settlement leg of a clearing batch: which journal carried the movement, for how much,
 * and whether a post-commit conflict unwound it.
 */
data class ClearingSettlementPosting(
    val batchId: UUID,
    val journalId: UUID,
    val amount: BigDecimal,
    val reversed: Boolean = false,
)

/**
 * Money-path clearing adoption: the invariant-side book for
 * [com.openbank.simulation.scenario.ClearingCycleScenario], mirroring BillingFeeLedger's role for
 * the fee-conservation invariant. Holds the REAL `clearing-service` aggregates
 * ([ClearingItem]/[ClearingBatch]/[ClearingCycleResult]) the scenario drives, plus the per-rail
 * cycle epoch the completeness invariant needs to tell "the item's rail simply has not cycled
 * since it was seeded" (PENDING is expected) from "a cycle ran for the item's rail after it was
 * seeded and silently skipped it" (the #11974/#12004 reported-not-dropped defect class).
 */
class ClearingCycleBook {

    val items = mutableListOf<ClearingItem>()
    val batches = mutableListOf<ClearingBatch>()
    val cycles = mutableListOf<ClearingCycleResult>()

    /** The net-settlement leg recorded per batch (present ⇔ the journal booked). */
    val postings: Map<UUID, ClearingSettlementPosting>
        get() = mutablePostings

    private val cycleEpochByRail = mutableMapOf<PaymentRail, Long>()
    private val seedEpochByItem = mutableMapOf<UUID, Long>()
    private val mutablePostings = mutableMapOf<UUID, ClearingSettlementPosting>()

    /** Seed an item, stamping the cycle epoch of its rail at seed time (see [seedEpochOf]). */
    fun recordItem(item: ClearingItem) {
        items.add(item)
        seedEpochByItem[item.id] = cycleEpochByRail[item.rail] ?: DEFAULT_EPOCH
    }

    fun recordBatch(batch: ClearingBatch) {
        batches.add(batch)
    }

    fun replaceItem(updated: ClearingItem) {
        val index = items.indexOfFirst { it.id == updated.id }
        items[index] = updated
    }

    fun replaceBatch(updated: ClearingBatch) {
        val index = batches.indexOfFirst { it.id == updated.id }
        batches[index] = updated
    }

    /** Record a cycle and advance its rail's epoch (see [cycleEpoch]). */
    fun recordCycle(result: ClearingCycleResult) {
        cycles.add(result)
        cycleEpochByRail[result.rail] = cycleEpoch(result.rail) + 1L
    }

    /** How many cycles have run for [rail] so far. */
    fun cycleEpoch(rail: PaymentRail): Long = cycleEpochByRail[rail] ?: DEFAULT_EPOCH

    /** The [cycleEpoch] of the item's rail at the moment it was seeded. */
    fun seedEpochOf(itemId: UUID): Long = seedEpochByItem[itemId] ?: DEFAULT_EPOCH

    fun recordPosting(batchId: UUID, journalId: UUID, amount: BigDecimal) {
        mutablePostings[batchId] = ClearingSettlementPosting(batchId, journalId, amount)
    }

    fun markReversed(batchId: UUID) {
        mutablePostings[batchId] = mutablePostings.getValue(batchId).copy(reversed = true)
    }

    fun itemsOf(batchId: UUID): List<ClearingItem> = items.filter { it.batchId == batchId }

    companion object {
        private const val DEFAULT_EPOCH = 0L
    }
}
