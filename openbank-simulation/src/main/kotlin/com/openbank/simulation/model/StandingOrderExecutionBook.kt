// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.simulation.model

import com.openbank.standingorder.domain.model.StandingOrder
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Simulated standing-order execution state (issue #12839 follow-up / Refs #12830): the real
 * `openbank-standing-order-service` `StandingOrder` aggregate per order, plus the two facts the
 * DST invariant needs that the aggregate itself does not carry:
 *
 * - how much money was actually posted per order (the ledger journals this scenario posted), so
 *   `MoneyPathInvariants.standingOrderExecutionConservation` can assert *Σ posted == Σ confirmed
 *   executions × order amount* — the standing-order analogue of billing-fee-conservation
 *   (ADR-0143 phase 2d): a replayed or duplicated execution must never move money twice, and a
 *   failed execution must never move money at all;
 * - which execution dates were attempted/confirmed, keyed exactly like the production
 *   idempotency key's date dimension (`so-exec-{orderId}-{executionDate}`), so a re-delivered
 *   due event is a *replay of the same attempt*, not a new one — the property that keeps a
 *   stuck ONCE order (issue #12839) from becoming many payments.
 */
class StandingOrderBook {

    /** The read-only query side: everything the invariant and the replay path need, per order. */
    data class OrderView(
        val order: StandingOrder?,
        val attemptedDates: Set<LocalDate>,
        val confirmedDates: Set<LocalDate>,
        val postedAmount: BigDecimal,
    )

    private val orders = mutableMapOf<UUID, StandingOrder>()
    private val posted = mutableMapOf<UUID, BigDecimal>()
    private val attempted = mutableMapOf<UUID, MutableSet<LocalDate>>()
    private val confirmed = mutableMapOf<UUID, MutableSet<LocalDate>>()

    fun all(): Collection<StandingOrder> = orders.values

    fun view(orderId: UUID): OrderView = OrderView(
        order = orders[orderId],
        attemptedDates = (attempted[orderId] ?: emptySet()).toSet(),
        confirmedDates = (confirmed[orderId] ?: emptySet()).toSet(),
        postedAmount = posted.getOrDefault(orderId, BigDecimal.ZERO),
    )

    /** Store a freshly placed order. */
    fun place(order: StandingOrder) {
        orders[order.id] = order
    }

    /** Store the aggregate after a domain transition (recordExecution/recordFailure/confirmExecution). */
    fun replace(order: StandingOrder) {
        orders[order.id] = order
    }

    /** Record one attempt at `(orderId, date)` — the `so-exec-` key's date dimension. */
    fun recordAttempt(orderId: UUID, date: LocalDate) {
        attempted.getOrPut(orderId) { mutableSetOf() }.add(date)
    }

    fun recordConfirmed(orderId: UUID, date: LocalDate) {
        confirmed.getOrPut(orderId) { mutableSetOf() }.add(date)
    }

    /** Record one posted payment journal for [orderId]. */
    fun recordPosted(orderId: UUID, amount: BigDecimal) {
        posted[orderId] = posted.getOrDefault(orderId, BigDecimal.ZERO) + amount
    }
}
