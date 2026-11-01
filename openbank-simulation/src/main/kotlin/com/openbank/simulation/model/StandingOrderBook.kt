// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import java.math.BigDecimal
import java.util.UUID

/**
 * Simulated standing-order execution state: per order, what the **real**
 * `com.openbank.standingorder.domain.model.StandingOrder` status machine recorded
 * (`executionCount` / `failureCount`) versus what actually happened on the money path. The
 * scenario writes both sides independently and `MoneyPathInvariants.standingOrderCompleteness`
 * reconciles them — the same both-sides bookkeeping shape as [BillingFeeLedger] (ADR-0143
 * phase 2d) and [InterestAccrualBook] (ADR-0033).
 *
 * Two failure counters are kept on purpose, mirroring the production consumer exactly
 * (StandingOrderDueConsumer): every failed attempt is an *outcome* of a claimed execution slot,
 * but the status machine only acknowledges it when `recordFailure` actually applies — the
 * consumer calls it through `runCatching` (`recordFailureSafely`), and a `require` rejection
 * (e.g. an order `recordExecution` just COMPLETED) is a logged warning, not a counter move. An
 * invariant over only one side would pass vacuously over an always-empty map — the trap
 * [BillingFeeLedger] documented when it made `billingFeeConservation` load-bearing.
 */
class StandingOrderBook {

    private data class Totals(
        var confirmed: Int = 0,
        var confirmedAmount: BigDecimal = BigDecimal.ZERO,
        var attemptedFailures: Int = 0,
        var machineAcknowledgedFailures: Int = 0,
        /** The live streak the status machine would carry: confirmExecution resets it to 0. */
        var machineStreak: Int = 0,
    )

    private val totals = mutableMapOf<UUID, Totals>()

    /**
     * One execution actually confirmed end-to-end (money moved). The machine's streak resets only
     * when the real `confirmExecution` applied ([machineConfirmed]): an order `recordExecution`
     * already COMPLETED (its end date passed) cannot be confirmed, so its `failureCount` — and
     * therefore the streak this book mirrors — stays where it was.
     */
    fun recordConfirmed(orderId: UUID, amount: BigDecimal, machineConfirmed: Boolean = true) {
        totals.computeIfAbsent(orderId) { Totals() }.let {
            it.confirmed += 1
            it.confirmedAmount = it.confirmedAmount.add(amount)
            if (machineConfirmed) it.machineStreak = 0
        }
    }

    /** One attempted execution whose money path failed (decline, write fault, conflict). */
    fun recordAttemptFailure(orderId: UUID) {
        totals.computeIfAbsent(orderId) { Totals() }.attemptedFailures += 1
    }

    /** The status machine actually accepted the failure (recordFailure applied, status ACTIVE). */
    fun recordMachineAcknowledgedFailure(orderId: UUID) {
        totals.computeIfAbsent(orderId) { Totals() }.let {
            it.machineAcknowledgedFailures += 1
            it.machineStreak += 1
        }
    }

    fun ids(): Set<UUID> = totals.keys

    fun confirmedCount(orderId: UUID): Int = totals[orderId]?.confirmed ?: 0

    fun confirmedAmount(orderId: UUID): BigDecimal = totals[orderId]?.confirmedAmount ?: BigDecimal.ZERO

    fun attemptedFailureCount(orderId: UUID): Int = totals[orderId]?.attemptedFailures ?: 0

    fun machineAcknowledgedFailureCount(orderId: UUID): Int = totals[orderId]?.machineAcknowledgedFailures ?: 0

    /** The failure streak the machine carries after the last confirmed execution — the exact
     *  semantics of the real `confirmExecution`, which resets `failureCount` to 0. */
    fun machineStreak(orderId: UUID): Int = totals[orderId]?.machineStreak ?: 0
}
