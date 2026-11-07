// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Simulated SDD collection state: per mandate, what the **real** mandate lifecycle recorded
 * (`lastCollectionDate`, `sequenceType` advancing FRST → RCUR via `MandateLifecycle
 * .recordCollection`) versus what the money path actually did — the amount the scenario intended
 * to collect, the amount that actually posted, and the failed attempts. The scenario writes all
 * sides independently and `MoneyPathInvariants.sddCollectionCompleteness` reconciles them — the
 * same both-sides bookkeeping shape as [BillingFeeLedger] (ADR-0143 phase 2d),
 * [InterestAccrualBook] (ADR-0033) and [StandingOrderBook]: a mandate stamped with a collection
 * the ledger never received (or a book entry for a collection the mandate never recorded) is
 * exactly the class of defect the invariant exists to catch, and an always-empty book would let
 * it pass vacuously.
 */
class SddCollectionBook {

    private data class Totals(
        var postedCollections: Int = 0,
        var postedAmount: BigDecimal = BigDecimal.ZERO,
        var attemptedFailures: Int = 0,
        var lastCollectionDate: LocalDate? = null,
    )

    private val totals = mutableMapOf<UUID, Totals>()

    /** A collection actually posted end-to-end (money moved, mandate stamped). */
    fun recordCollected(mandateId: UUID, amount: BigDecimal, date: LocalDate) {
        totals.computeIfAbsent(mandateId) { Totals() }.let {
            it.postedCollections += 1
            it.postedAmount = it.postedAmount.add(amount)
            it.lastCollectionDate = date
        }
    }

    /** A collection attempt whose money path failed (decline, write fault, post-commit conflict). */
    fun recordAttemptFailure(mandateId: UUID) {
        totals.computeIfAbsent(mandateId) { Totals() }.attemptedFailures += 1
    }

    fun ids(): Set<UUID> = totals.keys

    fun postedCollections(mandateId: UUID): Int = totals[mandateId]?.postedCollections ?: 0

    fun postedAmount(mandateId: UUID): BigDecimal = totals[mandateId]?.postedAmount ?: BigDecimal.ZERO

    fun attemptedFailureCount(mandateId: UUID): Int = totals[mandateId]?.attemptedFailures ?: 0

    fun lastCollectionDate(mandateId: UUID): LocalDate? = totals[mandateId]?.lastCollectionDate
}
